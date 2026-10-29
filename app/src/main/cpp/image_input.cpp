#include "image_input.h"

#include <cmath>
#include <cstdio>
#include <cstring>

#define STBI_NO_STDIO
#define STBI_ONLY_JPEG
#define STBI_ONLY_PNG
#define STBI_NO_LINEAR
#define STBI_NO_HDR
#define STB_IMAGE_IMPLEMENTATION
#include "stb_image.h"

namespace rp {

namespace {

constexpr size_t kMaxFileBytes = 512u * 1024u * 1024u;

// Lector con comprobación de límites sobre el bloque TIFF del EXIF.
struct Tiff {
    const uint8_t* d;
    size_t n;
    bool le;

    bool u16(size_t off, uint16_t& v) const {
        if (off > n || n - off < 2) return false;
        v = le ? static_cast<uint16_t>(d[off] | (d[off + 1] << 8))
               : static_cast<uint16_t>((d[off] << 8) | d[off + 1]);
        return true;
    }
    bool u32(size_t off, uint32_t& v) const {
        if (off > n || n - off < 4) return false;
        v = le ? (static_cast<uint32_t>(d[off]) | (static_cast<uint32_t>(d[off + 1]) << 8) |
                  (static_cast<uint32_t>(d[off + 2]) << 16) | (static_cast<uint32_t>(d[off + 3]) << 24))
               : ((static_cast<uint32_t>(d[off]) << 24) | (static_cast<uint32_t>(d[off + 1]) << 16) |
                  (static_cast<uint32_t>(d[off + 2]) << 8) | static_cast<uint32_t>(d[off + 3]));
        return true;
    }
};

struct Entry {
    uint16_t tag = 0, type = 0;
    uint32_t count = 0;
    size_t valueOff = 0;  // dónde están los datos (en línea o apuntados)
};

size_t typeSize(uint16_t t) {
    switch (t) {
        case 1: case 2: case 6: case 7: return 1;
        case 3: case 8: return 2;
        case 4: case 9: case 11: return 4;
        case 5: case 10: case 12: return 8;
        default: return 0;
    }
}

// Recorre un IFD y llama a fn(entrada) por cada una. Límite de entradas por seguridad.
template <typename Fn>
void forEachEntry(const Tiff& t, size_t ifdOff, Fn fn) {
    uint16_t count;
    if (!t.u16(ifdOff, count) || count > 1024) return;
    for (uint16_t i = 0; i < count; ++i) {
        size_t e = ifdOff + 2 + static_cast<size_t>(i) * 12;
        Entry en;
        uint32_t valueField;
        if (!t.u16(e, en.tag) || !t.u16(e + 2, en.type) || !t.u32(e + 4, en.count) ||
            !t.u32(e + 8, valueField))
            return;
        size_t ts = typeSize(en.type);
        if (ts == 0) continue;
        size_t total = ts * static_cast<size_t>(en.count);
        if (en.count != 0 && total / en.count != ts) continue;  // desbordamiento
        en.valueOff = total <= 4 ? e + 8 : valueField;
        if (en.valueOff > t.n || t.n - en.valueOff < total) continue;  // fuera del búfer
        fn(en);
    }
}

std::string readAscii(const Tiff& t, const Entry& e) {
    std::string s(reinterpret_cast<const char*>(t.d + e.valueOff), e.count);
    size_t z = s.find('\0');
    if (z != std::string::npos) s.resize(z);
    while (!s.empty() && (s.back() == ' ' || s.back() == '\n' || s.back() == '\r')) s.pop_back();
    size_t b = 0;
    while (b < s.size() && s[b] == ' ') ++b;
    return s.substr(b);
}

double readRational(const Tiff& t, const Entry& e) {
    uint32_t num, den;
    if (e.type != 5 || e.count < 1 || !t.u32(e.valueOff, num) || !t.u32(e.valueOff + 4, den) || den == 0)
        return 0;
    return static_cast<double>(num) / static_cast<double>(den);
}

bool readShort(const Tiff& t, const Entry& e, uint16_t& v) {
    return (e.type == 3 && e.count >= 1 && t.u16(e.valueOff, v));
}

// Tabla sRGB (8 bits) -> lineal (16 bits).
const uint16_t* srgbToLinear16() {
    static const std::vector<uint16_t> lut = [] {
        std::vector<uint16_t> t(256);
        for (int i = 0; i < 256; ++i) {
            double c = i / 255.0;
            double lin = c <= 0.04045 ? c / 12.92 : std::pow((c + 0.055) / 1.055, 2.4);
            t[i] = static_cast<uint16_t>(std::lround(lin * 65535.0));
        }
        return t;
    }();
    return lut.data();
}

}  // namespace

bool looksLikeJpegOrPng(const uint8_t* d, size_t n) {
    if (n >= 3 && d[0] == 0xFF && d[1] == 0xD8 && d[2] == 0xFF) return true;
    static const uint8_t png[8] = {0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    return n >= 8 && std::memcmp(d, png, 8) == 0;
}

bool parseJpegExif(const uint8_t* d, size_t n, ExifInfo& out) {
    if (n < 4 || d[0] != 0xFF || d[1] != 0xD8) return false;
    size_t pos = 2;
    while (pos + 4 <= n) {
        if (d[pos] != 0xFF) return false;
        uint8_t m = d[pos + 1];
        if (m == 0xFF) { ++pos; continue; }  // relleno
        if (m == 0xDA || m == 0xD9) return false;  // datos de imagen: ya no hay más metadatos
        if (m == 0x01 || (m >= 0xD0 && m <= 0xD7)) { pos += 2; continue; }
        size_t len = (static_cast<size_t>(d[pos + 2]) << 8) | d[pos + 3];
        if (len < 2 || pos + 2 + len > n) return false;
        if (m == 0xE1 && len >= 14 && std::memcmp(d + pos + 4, "Exif\0\0", 6) == 0) {
            Tiff t{d + pos + 10, len - 8, true};
            if (t.n < 8) return false;
            if (t.d[0] == 'I' && t.d[1] == 'I') t.le = true;
            else if (t.d[0] == 'M' && t.d[1] == 'M') t.le = false;
            else return false;
            uint16_t magic;
            uint32_t ifd0;
            if (!t.u16(2, magic) || magic != 42 || !t.u32(4, ifd0)) return false;

            uint32_t exifIfd = 0;
            forEachEntry(t, ifd0, [&](const Entry& e) {
                uint16_t s;
                switch (e.tag) {
                    case 0x010F: if (e.type == 2) out.make = readAscii(t, e); break;
                    case 0x0110: if (e.type == 2) out.model = readAscii(t, e); break;
                    case 0x0112:
                        if (readShort(t, e, s) && s >= 1 && s <= 8) out.orientation = s;
                        break;
                    case 0x8769: t.u32(e.valueOff, exifIfd); break;
                }
            });
            if (exifIfd != 0) {
                forEachEntry(t, exifIfd, [&](const Entry& e) {
                    uint16_t s;
                    switch (e.tag) {
                        case 0x829A: out.shutter = readRational(t, e); break;
                        case 0x829D: out.aperture = readRational(t, e); break;
                        case 0x8827: if (readShort(t, e, s)) out.iso = s; break;
                        case 0x920A: out.focal = readRational(t, e); break;
                    }
                });
            }
            return true;
        }
        pos += 2 + len;
    }
    return false;
}

void applyExifOrientation(const uint8_t* rgb, int w, int h, int orientation,
                          std::vector<uint8_t>& out, int& ow, int& oh) {
    if (orientation < 1 || orientation > 8) orientation = 1;
    const bool swap = orientation >= 5;
    ow = swap ? h : w;
    oh = swap ? w : h;
    out.resize(static_cast<size_t>(ow) * oh * 3);
    for (int oy = 0; oy < oh; ++oy) {
        for (int ox = 0; ox < ow; ++ox) {
            int sx, sy;
            switch (orientation) {
                case 2: sx = w - 1 - ox; sy = oy; break;               // espejo horizontal
                case 3: sx = w - 1 - ox; sy = h - 1 - oy; break;       // 180°
                case 4: sx = ox; sy = h - 1 - oy; break;               // espejo vertical
                case 5: sx = oy; sy = ox; break;                       // transpuesta
                case 6: sx = oy; sy = h - 1 - ox; break;               // 90° horario
                case 7: sx = w - 1 - oy; sy = h - 1 - ox; break;       // transversal
                case 8: sx = w - 1 - oy; sy = ox; break;               // 90° antihorario
                default: sx = ox; sy = oy; break;
            }
            const uint8_t* s = rgb + (static_cast<size_t>(sy) * w + sx) * 3;
            uint8_t* d = &out[(static_cast<size_t>(oy) * ow + ox) * 3];
            d[0] = s[0]; d[1] = s[1]; d[2] = s[2];
        }
    }
}

int decodeImageFile(const std::string& path, std::vector<uint16_t>& rgb16, int& w, int& h,
                    ExifInfo& info) {
    std::vector<uint8_t> file;
    {
        FILE* f = std::fopen(path.c_str(), "rb");
        if (!f) return kErrImageRead;
        std::fseek(f, 0, SEEK_END);
        long size = std::ftell(f);
        std::fseek(f, 0, SEEK_SET);
        if (size <= 0 || static_cast<size_t>(size) > kMaxFileBytes) { std::fclose(f); return kErrImageRead; }
        file.resize(static_cast<size_t>(size));
        size_t got = std::fread(file.data(), 1, file.size(), f);
        std::fclose(f);
        if (got != file.size()) return kErrImageRead;
    }
    if (!looksLikeJpegOrPng(file.data(), file.size())) return kErrImageDecode;
    if (file[0] == 0xFF) parseJpegExif(file.data(), file.size(), info);

    int iw = 0, ih = 0, comp = 0;
    stbi_uc* px = stbi_load_from_memory(file.data(), static_cast<int>(file.size()), &iw, &ih, &comp, 3);
    file.clear();
    file.shrink_to_fit();
    if (!px || iw <= 0 || ih <= 0) {
        if (px) stbi_image_free(px);
        return kErrImageDecode;
    }

    const uint8_t* src = px;
    std::vector<uint8_t> oriented;
    w = iw;
    h = ih;
    if (info.orientation != 1) {
        applyExifOrientation(px, iw, ih, info.orientation, oriented, w, h);
        stbi_image_free(px);
        px = nullptr;
        src = oriented.data();
    }

    const uint16_t* lut = srgbToLinear16();
    rgb16.resize(static_cast<size_t>(w) * h * 3);
    const size_t n = static_cast<size_t>(w) * h * 3;
    for (size_t i = 0; i < n; ++i) rgb16[i] = lut[src[i]];
    if (px) stbi_image_free(px);
    return 0;
}

}  // namespace rp
