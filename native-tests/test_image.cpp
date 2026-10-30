// Pruebas de entrada JPG/PNG: EXIF, orientación, decodificación, sesión y miniatura.
#include <cmath>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "image_input.h"
#include "raw_session.h"
#include "stb_image_write.h"

static int fails = 0;
#define CHECK(c) do { if (!(c)) { std::printf("  FALLA línea %d: %s\n", __LINE__, #c); ++fails; } } while (0)

static void wr(void* ctx, void* d, int n) {
    auto* v = static_cast<std::vector<uint8_t>*>(ctx);
    v->insert(v->end(), (uint8_t*)d, (uint8_t*)d + n);
}
static void put(const std::string& p, const std::vector<uint8_t>& d) {
    FILE* f = std::fopen(p.c_str(), "wb");
    std::fwrite(d.data(), 1, d.size(), f);
    std::fclose(f);
}
// TIFF little-endian mínimo: IFD0 {make, model, orientation, ExifIFD} + ExifIFD {iso, shutter, aperture, focal}
static std::vector<uint8_t> makeExif(int orientation) {
    std::vector<uint8_t> t;
    auto u16 = [&](unsigned v) { t.push_back(v & 255); t.push_back(v >> 8); };
    auto u32 = [&](uint32_t v) { for (int i = 0; i < 4; ++i) t.push_back((v >> (8 * i)) & 255); };
    t.push_back('I'); t.push_back('I'); u16(42); u32(8);
    // IFD0 en 8: 4 entradas -> 2 + 48 + 4 = 54 -> termina en 62
    const uint32_t makeOff = 62, modelOff = 70, exifOff = 80;
    u16(4);
    u16(0x010F); u16(2); u32(6); u32(makeOff);
    u16(0x0110); u16(2); u32(8); u32(modelOff);
    u16(0x0112); u16(3); u32(1); u16(orientation); u16(0);
    u16(0x8769); u16(4); u32(1); u32(exifOff);
    u32(0);
    const char* mk = "Canon"; for (int i = 0; i < 6; ++i) t.push_back(mk[i]);  // 62..67
    t.push_back(0); t.push_back(0);                                           // pad -> 70
    const char* md = "EOS R6"; for (int i = 0; i < 7; ++i) t.push_back(md[i]); // 70..76
    while (t.size() < exifOff) t.push_back(0);
    // ExifIFD en 80: 4 entradas -> 2+48+4 = 54 -> datos desde 134
    u16(4);
    u16(0x829A); u16(5); u32(1); u32(134);
    u16(0x829D); u16(5); u32(1); u32(142);
    u16(0x8827); u16(3); u32(1); u16(800); u16(0);
    u16(0x920A); u16(5); u32(1); u32(150);
    u32(0);
    u32(1); u32(250);  // 1/250
    u32(28); u32(10);  // f/2.8
    u32(85); u32(1);   // 85 mm
    return t;
}
static std::vector<uint8_t> makeJpeg(int w, int h, const std::vector<uint8_t>& rgb, int orientation) {
    std::vector<uint8_t> j;
    stbi_write_jpg_to_func(wr, &j, w, h, 3, rgb.data(), 100);
    if (orientation == 0) return j;
    auto tiff = makeExif(orientation);
    std::vector<uint8_t> app = {0xFF, 0xE1};
    size_t len = 2 + 6 + tiff.size();
    app.push_back(len >> 8); app.push_back(len & 255);
    for (char c : std::string("Exif")) app.push_back(c);
    app.push_back(0); app.push_back(0);
    app.insert(app.end(), tiff.begin(), tiff.end());
    j.insert(j.begin() + 2, app.begin(), app.end());
    return j;
}

int main(int argc, char** argv) {
    std::string dir = argc > 1 ? argv[1] : ".";
    // Imagen 8x4 con bloques de color planos (el JPEG q100 los conserva casi exactos)
    const int W = 8, H = 4;
    std::vector<uint8_t> rgb(W * H * 3);
    for (int y = 0; y < H; ++y)
        for (int x = 0; x < W; ++x) {
            uint8_t* p = &rgb[(y * W + x) * 3];
            p[0] = x < 4 ? 220 : 30; p[1] = y < 2 ? 200 : 40; p[2] = 90;
        }

    std::printf("[EXIF] lectura\n");
    {
        auto j = makeJpeg(W, H, rgb, 6);
        rp::ExifInfo e;
        CHECK(rp::parseJpegExif(j.data(), j.size(), e));
        CHECK(e.make == "Canon" && e.model == "EOS R6");
        CHECK(e.orientation == 6 && e.iso == 800);
        CHECK(std::fabs(e.shutter - 0.004) < 1e-9 && std::fabs(e.aperture - 2.8) < 1e-9 && e.focal == 85);
        // truncado en cualquier punto: no debe fallar ni leer fuera del búfer
        for (size_t n = 0; n < 200 && n < j.size(); ++n) { rp::ExifInfo x; rp::parseJpegExif(j.data(), n, x); }
        // bytes corrompidos
        for (size_t i = 20; i < 140; ++i) { auto c = j; c[i] ^= 0xFF; rp::ExifInfo x; rp::parseJpegExif(c.data(), c.size(), x); }
        auto plain = makeJpeg(W, H, rgb, 0);
        rp::ExifInfo n; CHECK(!rp::parseJpegExif(plain.data(), plain.size(), n) && n.orientation == 1);
    }

    std::printf("[EXIF] orientación 1..8\n");
    {
        for (int o = 1; o <= 8; ++o) {
            std::vector<uint8_t> out; int ow, oh;
            rp::applyExifOrientation(rgb.data(), W, H, o, out, ow, oh);
            CHECK(o >= 5 ? (ow == H && oh == W) : (ow == W && oh == H));
            // esquina superior izquierda del original (x=0,y=0) -> dónde cae
            // 1:(0,0) 2:(W-1,0) 3:(W-1,H-1) 4:(0,H-1) 5:(0,0) 6:(H-1,0) 7:(H-1,W-1) 8:(0,W-1)
            int tx[] = {0, 0, W - 1, W - 1, 0, 0, H - 1, H - 1, 0};
            int ty[] = {0, 0, 0, H - 1, H - 1, 0, 0, W - 1, W - 1};
            const uint8_t* q = &out[(ty[o] * ow + tx[o]) * 3];
            CHECK(q[0] == 220 && q[1] == 200);
        }
        std::vector<uint8_t> out; int ow, oh;
        rp::applyExifOrientation(rgb.data(), W, H, 99, out, ow, oh);
        CHECK(ow == W && out == rgb);
    }

    std::printf("[sesión] abrir JPG con orientación, miniatura y exportar\n");
    {
        std::string jp = dir + "/t_rot.jpg";
        put(jp, makeJpeg(W, H, rgb, 6));
        std::unique_ptr<rp::Session> s;
        CHECK(rp::openSession(jp, 2048, s) == 0);
        CHECK(s && s->pw == H && s->ph == W);  // 6 gira 90°: 4x8
        CHECK(s->info.rfind("Canon|EOS R6|800|", 0) == 0);
        std::string th = dir + "/t_thumb.jpg";
        CHECK(rp::exportThumbnail(*s, th, 2, 80) == 0);
        FILE* f = std::fopen(th.c_str(), "rb"); CHECK(f != nullptr); if (f) std::fclose(f);
        rp::Params p;
        std::string ex = dir + "/t_out.jpg";
        CHECK(rp::exportJpeg(*s, p, ex, 90, 0) == 0);

        // exportar con efectos con vecinos + viñeta + grano
        {
            rp::Params fx; fx.clarity = 0.6f; fx.sharpen = 0.5f; fx.nrLuma = 0.3f; fx.vigAmount = -0.6f; fx.grain = 0.5f;
            std::string ef = dir + "/t_fx.jpg";
            CHECK(rp::exportJpeg(*s, fx, ef, 90, 0) == 0);
            auto fileSize = [](const std::string& path) { FILE* f = std::fopen(path.c_str(), "rb"); if (!f) return -1L; std::fseek(f, 0, SEEK_END); long n = std::ftell(f); std::fclose(f); return n; };
            CHECK(fileSize(ef) > 100 && fileSize(ef) != fileSize(ex));
            rp::ensureAux(*s);
            CHECK(!s->aux.empty() && s->aux.w == s->pw && s->aux.h == s->ph);
        }

        // PNG
        std::vector<uint8_t> png;
        stbi_write_png_to_func(wr, &png, W, H, 3, rgb.data(), W * 3);
        std::string pp = dir + "/t.png";
        put(pp, png);
        std::unique_ptr<rp::Session> s2;
        CHECK(rp::openSession(pp, 2048, s2) == 0 && s2->pw == W && s2->ph == H);
        // blanco/negro: lineal 16 bits exacto en los extremos
        std::vector<uint8_t> bw(2 * 1 * 3, 0); bw[3] = bw[4] = bw[5] = 255;
        std::vector<uint8_t> bwpng; stbi_write_png_to_func(wr, &bwpng, 2, 1, 3, bw.data(), 6);
        put(dir + "/t_bw.png", bwpng);
        std::vector<uint16_t> v; int w, h; rp::ExifInfo e;
        CHECK(rp::decodeImageFile(dir + "/t_bw.png", v, w, h, e) == 0 && v[0] == 0 && v[3] == 65535);

        // errores
        std::string bad = dir + "/t_bad.jpg";
        put(bad, {0xFF, 0xD8, 0xFF, 0x00, 0x01});
        std::unique_ptr<rp::Session> s3;
        CHECK(rp::openSession(bad, 2048, s3) != 0);
        CHECK(rp::openSession(dir + "/no_existe.jpg", 2048, s3) != 0);
    }
    std::printf(fails ? "FALLÓ (%d)\n" : "OK\n", fails);
    return fails ? 1 : 0;
}
