#include "raw_session.h"

#include <algorithm>
#include <cstdio>
#include <new>
#include <thread>

#include "image_input.h"
#include "libraw/libraw.h"

#define STB_IMAGE_WRITE_IMPLEMENTATION
#define STBI_WRITE_NO_STDIO
#include "stb_image_write.h"

namespace rp {

namespace {

// Imagen decodificada (RGB lineal 16 bits): de LibRaw (se libera con dcraw_clear_mem) o de un
// JPEG/PNG (vector propio).
struct Decoded {
    libraw_processed_image_t* img = nullptr;
    std::vector<uint16_t> own;
    int ow = 0, oh = 0;
    std::string info;
    Decoded() = default;
    Decoded(const Decoded&) = delete;
    Decoded& operator=(const Decoded&) = delete;
    ~Decoded() {
        if (img) LibRaw::dcraw_clear_mem(img);
    }
    const uint16_t* data() const {
        return img ? reinterpret_cast<const uint16_t*>(img->data) : own.data();
    }
    int width() const { return img ? img->width : ow; }
    int height() const { return img ? img->height : oh; }
};

bool isJpegOrPng(const char* path) {
    FILE* f = std::fopen(path, "rb");
    if (!f) return false;
    uint8_t head[8] = {0};
    size_t n = std::fread(head, 1, sizeof(head), f);
    std::fclose(f);
    return looksLikeJpegOrPng(head, n);
}

int decodeStill(const char* path, Decoded& out) {
    ExifInfo ex;
    int rc = decodeImageFile(path, out.own, out.ow, out.oh, ex);
    if (rc != 0) return rc;
    char buf[512];
    std::snprintf(buf, sizeof(buf), "%s|%s|%.0f|%.6f|%.2f|%.1f|%d|%d", ex.make.c_str(),
                  ex.model.c_str(), ex.iso, ex.shutter, ex.aperture, ex.focal, out.ow, out.oh);
    out.info = buf;
    return 0;
}

// half = true usa media resolución (rápido, para la vista previa).
int decode(const char* path, bool half, Decoded& out) {
    if (isJpegOrPng(path)) return decodeStill(path, out);
    LibRaw lr;
    auto& P = lr.imgdata.params;
    P.use_camera_wb = 1;  // el balance de la cámara es el punto de partida
    P.use_auto_wb = 0;
    P.output_bps = 16;
    P.output_color = 1;  // sRGB
    P.gamm[0] = 1.0;     // salida lineal: la curva tonal la aplica nuestro motor
    P.gamm[1] = 1.0;
    P.no_auto_bright = 1;
    P.highlight = 0;
    P.user_qual = 3;  // AHD
    P.half_size = half ? 1 : 0;

    int rc = lr.open_file(path);
    if (rc != LIBRAW_SUCCESS) return rc;

    {
        const auto& S = lr.imgdata.sizes;
        int fw = S.width, fh = S.height;
        if (S.flip & 4) std::swap(fw, fh);
        const char* model = lr.imgdata.idata.normalized_model[0] ? lr.imgdata.idata.normalized_model
                                                                  : lr.imgdata.idata.model;
        char buf[512];
        std::snprintf(buf, sizeof(buf), "%s|%s|%.0f|%.6f|%.2f|%.1f|%d|%d", lr.imgdata.idata.make,
                      model, lr.imgdata.other.iso_speed, lr.imgdata.other.shutter,
                      lr.imgdata.other.aperture, lr.imgdata.other.focal_len, fw, fh);
        out.info = buf;
    }

    rc = lr.unpack();
    if (rc != LIBRAW_SUCCESS) return rc;
    rc = lr.dcraw_process();
    if (rc != LIBRAW_SUCCESS) return rc;

    int err = 0;
    libraw_processed_image_t* img = lr.dcraw_make_mem_image(&err);  // ya aplica la orientación
    if (!img) return err ? err : LIBRAW_UNSPECIFIED_ERROR;
    if (img->type != LIBRAW_IMAGE_BITMAP || img->colors != 3 || img->bits != 16) {
        LibRaw::dcraw_clear_mem(img);
        return kErrUnsupportedImage;
    }
    out.img = img;
    return LIBRAW_SUCCESS;  // `lr` libera su buffer interno al salir; `img` es independiente
}

void writeToFile(void* ctx, void* data, int size) {
    std::fwrite(data, 1, static_cast<size_t>(size), static_cast<FILE*>(ctx));
}

}  // namespace

int workerThreads() {
    unsigned n = std::thread::hardware_concurrency();
    return static_cast<int>(std::max(1u, std::min(n, 4u)));
}

int openSession(const std::string& path, int previewLongEdge, std::unique_ptr<Session>& out) {
    auto s = std::make_unique<Session>();
    s->path = path;

    Decoded dec;
    int rc;
    try {
        rc = decode(path.c_str(), /*half=*/true, dec);
    } catch (const std::bad_alloc&) {
        return LIBRAW_UNSUFFICIENT_MEMORY;
    } catch (...) {
        return LIBRAW_UNSPECIFIED_ERROR;
    }
    if (rc > 0) rc = kErrImageRead;  // errno del sistema: se trata como error de lectura
    if (rc != 0) return rc;

    int longEdge = std::max(dec.width(), dec.height());
    int target = previewLongEdge > 0 ? previewLongEdge : 2048;
    int step = std::max(1, (longEdge + target - 1) / target);
    boxDownsample16(dec.data(), dec.width(), dec.height(), step, s->preview, s->pw, s->ph);
    {
        int smallStep = std::max(1, (std::max(s->pw, s->ph) + 511) / 512);
        boxDownsample16(s->preview.data(), s->pw, s->ph, smallStep, s->small, s->sw, s->sh);
    }
    s->info = dec.info;
    out = std::move(s);
    return 0;
}

int exportJpeg(const Session& s, const Params& p, const std::string& outPath, int quality,
               int maxEdge) {
    std::vector<uint16_t> reduced;
    std::vector<uint8_t> rgb;
    int w = 0, h = 0;
    {
        Decoded dec;
        int rc = decode(s.path.c_str(), /*half=*/false, dec);
        if (rc != 0) return rc;

        const uint16_t* src = dec.data();
        w = dec.width();
        h = dec.height();
        if (maxEdge > 0 && std::max(w, h) > maxEdge) {
            int step = (std::max(w, h) + maxEdge - 1) / maxEdge;
            int rw = 0, rh = 0;
            boxDownsample16(src, w, h, step, reduced, rw, rh);
            src = reduced.data();
            w = rw;
            h = rh;
        }
        // Los efectos con vecinos necesitan sus capas auxiliares; a media resolución si la foto es
        // muy grande (los desenfoques son suaves) para no duplicar la memoria.
        Aux aux;
        if (p.hasSpatial()) buildAux(src, w, h, std::max(w, h) > 3072 ? 2 : 1, aux);
        const Aux* ax = aux.empty() ? nullptr : &aux;
        if (p.hasGeometry()) {
            int ow = 0, oh = 0;
            outputSize(w, h, p, ow, oh);
            rgb.resize(static_cast<size_t>(ow) * oh * 3);
            developGeometry(src, w, h, p, rgb.data(), ow, oh, workerThreads(), ax);
            w = ow;
            h = oh;
        } else {
            rgb.resize(static_cast<size_t>(w) * h * 3);
            developToRgb8(src, w, h, p, rgb.data(), workerThreads(), ax);
        }
    }  // la imagen de 16 bits se libera antes de codificar

    FILE* f = std::fopen(outPath.c_str(), "wb");
    if (!f) return kErrWrite;
    int q = std::max(1, std::min(100, quality));
    int ok = stbi_write_jpg_to_func(writeToFile, f, w, h, 3, rgb.data(), q);
    std::fclose(f);
    return ok ? 0 : kErrWrite;
}

void ensureAux(Session& s) {
    if (s.aux.empty() && !s.preview.empty()) buildAux(s.preview.data(), s.pw, s.ph, 1, s.aux);
}

int exportThumbnail(const Session& s, const std::string& outPath, int edge, int quality) {
    if (s.preview.empty() || edge <= 0) return kErrBadArgs;
    std::vector<uint16_t> reduced;
    int rw = 0, rh = 0;
    int step = std::max(1, (std::max(s.pw, s.ph) + edge - 1) / edge);
    boxDownsample16(s.preview.data(), s.pw, s.ph, step, reduced, rw, rh);
    std::vector<uint8_t> rgb(static_cast<size_t>(rw) * rh * 3);
    developToRgb8(reduced.data(), rw, rh, Params(), rgb.data(), 1);
    FILE* f = std::fopen(outPath.c_str(), "wb");
    if (!f) return kErrWrite;
    int ok = stbi_write_jpg_to_func(writeToFile, f, rw, rh, 3, rgb.data(), std::max(1, std::min(100, quality)));
    std::fclose(f);
    return ok ? 0 : kErrWrite;
}

std::string errorText(int code) {
    switch (code) {
        case kErrImageDecode: return "No se pudo leer la imagen (¿JPG/PNG dañado?)";
        case kErrImageRead: return "No se pudo leer el archivo";
        case kErrBadArgs: return "Argumentos inválidos";
        case kErrUnsupportedImage: return "Formato de imagen no soportado";
        case kErrBitmap: return "No se pudo acceder al bitmap";
        case kErrWrite: return "No se pudo escribir el archivo";
        case LIBRAW_FILE_UNSUPPORTED: return "Formato RAW no soportado por esta versión";
        case LIBRAW_IO_ERROR: return "No se pudo leer el archivo (¿dañado o incompleto?)";
        case LIBRAW_UNSUFFICIENT_MEMORY: return "Memoria insuficiente para procesar este RAW";
        case LIBRAW_DATA_ERROR: return "Los datos del RAW están dañados";
        default: {
            std::string t = libraw_strerror(code);
            return t == "Unknown error code" ? "Error desconocido" : t;
        }
    }
}

}  // namespace rp
