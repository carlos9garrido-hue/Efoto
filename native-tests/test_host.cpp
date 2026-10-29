// Prueba en PC del núcleo nativo (sin Android): decodifica un DNG sintético, revela y exporta.
// Uso: test_host <archivo.dng> <archivo_o6.dng> <directorio_salida>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <string>

#include "develop.h"
#include "raw_session.h"

static int failures = 0;
#define CHECK(cond, msg)                                              \
    do {                                                              \
        if (!(cond)) { std::printf("  FAIL: %s\n", msg); ++failures; } \
        else { std::printf("  ok:   %s\n", msg); }                    \
    } while (0)

// Lee el tamaño de un JPEG (marcador SOF0/SOF1/SOF2).
static bool jpegSize(const std::string& path, int& w, int& h) {
    FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) return false;
    std::vector<uint8_t> d;
    uint8_t buf[4096];
    size_t n;
    while ((n = std::fread(buf, 1, sizeof(buf), f)) > 0) d.insert(d.end(), buf, buf + n);
    std::fclose(f);
    for (size_t i = 2; i + 9 < d.size();) {
        if (d[i] != 0xFF) { ++i; continue; }
        uint8_t m = d[i + 1];
        if (m == 0xC0 || m == 0xC1 || m == 0xC2) {
            h = (d[i + 5] << 8) | d[i + 6];
            w = (d[i + 7] << 8) | d[i + 8];
            return true;
        }
        if (m == 0xD8 || m == 0x01 || (m >= 0xD0 && m <= 0xD7)) { i += 2; continue; }
        i += 2 + ((d[i + 2] << 8) | d[i + 3]);
    }
    return false;
}

static float luma(const uint8_t* p) { return 0.2126f * p[0] + 0.7152f * p[1] + 0.0722f * p[2]; }

int main(int argc, char** argv) {
    if (argc < 4) { std::printf("uso: %s a.dng a_o6.dng outdir\n", argv[0]); return 2; }
    std::string a = argv[1], a6 = argv[2], outdir = argv[3];

    std::printf("[geometría] giro, enderezado y recorte\n");
    {
        // Imagen 4x2 con 8 colores distintos (RGB16 lineal).
        const int W = 4, H = 2;
        std::vector<uint16_t> src(W * H * 3);
        for (int n = 0; n < W * H; ++n) {
            src[n * 3 + 0] = static_cast<uint16_t>(4000 + 7000 * n);
            src[n * 3 + 1] = static_cast<uint16_t>(60000 - 6000 * n);
            src[n * 3 + 2] = static_cast<uint16_t>(2000 + 3000 * ((n * 5) % 8));
        }
        auto geo = [&](const std::vector<uint16_t>& s, int w, int h, const rp::Params& p,
                       std::vector<uint8_t>& out, int& ow, int& oh) {
            rp::outputSize(w, h, p, ow, oh);
            out.assign(static_cast<size_t>(ow) * oh * 3, 0);
            rp::developGeometry(s.data(), w, h, p, out.data(), ow, oh, 1);
        };
        auto plain = [&](const std::vector<uint16_t>& s, int w, int h) {
            std::vector<uint8_t> out(static_cast<size_t>(w) * h * 3);
            rp::developToRgb8(s.data(), w, h, rp::Params(), out.data(), 1);
            return out;
        };
        // Reordena píxeles: dst(i, j) = src(sx(i,j), sy(i,j)).
        auto remap = [&](int ow, int oh, auto fn) {
            std::vector<uint16_t> d(static_cast<size_t>(ow) * oh * 3);
            for (int j = 0; j < oh; ++j)
                for (int i = 0; i < ow; ++i) {
                    int sx, sy;
                    fn(i, j, sx, sy);
                    for (int c = 0; c < 3; ++c) d[(static_cast<size_t>(j) * ow + i) * 3 + c] = src[(sy * W + sx) * 3 + c];
                }
            return d;
        };

        int ow, oh;
        std::vector<uint8_t> out;
        rp::Params p;
        rp::outputSize(W, H, p, ow, oh);
        CHECK(ow == 4 && oh == 2, "tamaño sin cambios");
        p.rotate90 = 1;
        rp::outputSize(W, H, p, ow, oh);
        CHECK(ow == 2 && oh == 4, "girar 90° intercambia ancho y alto");
        p = rp::Params(); p.cropL = 0.5f;
        rp::outputSize(W, H, p, ow, oh);
        CHECK(ow == 2 && oh == 2, "recorte del 50% por la izquierda: 2x2");
        p = rp::Params(); p.angle = 20.f;
        rp::outputSize(W, H, p, ow, oh);
        CHECK(ow == 4 && oh == 2, "enderezar no cambia el tamaño del marco");

        // Giros: la foto gira en sentido horario (k=1: la parte superior pasa a la derecha).
        p = rp::Params(); p.rotate90 = 1;
        geo(src, W, H, p, out, ow, oh);
        auto r1 = remap(2, 4, [&](int i, int j, int& sx, int& sy) { sx = j; sy = H - 1 - i; });
        CHECK(out == plain(r1, 2, 4), "giro 90° horario: píxeles en su sitio");
        p.rotate90 = 2;
        geo(src, W, H, p, out, ow, oh);
        auto r2 = remap(4, 2, [&](int i, int j, int& sx, int& sy) { sx = W - 1 - i; sy = H - 1 - j; });
        CHECK(out == plain(r2, 4, 2), "giro 180°: píxeles en su sitio");
        p.rotate90 = 3;
        geo(src, W, H, p, out, ow, oh);
        auto r3 = remap(2, 4, [&](int i, int j, int& sx, int& sy) { sx = W - 1 - j; sy = i; });
        CHECK(out == plain(r3, 2, 4), "giro 270° (90° antihorario): píxeles en su sitio");
        p.rotate90 = 4;  // da la vuelta completa
        geo(src, W, H, p, out, ow, oh);
        CHECK(out == plain(src, W, H), "4 giros de 90° = sin giro");

        // Recorte: mitad derecha.
        p = rp::Params(); p.cropL = 0.5f;
        geo(src, W, H, p, out, ow, oh);
        auto rc = remap(2, 2, [&](int i, int j, int& sx, int& sy) { sx = 2 + i; sy = j; });
        CHECK(out == plain(rc, 2, 2), "recorte izquierdo 50%: queda la mitad derecha");
        p = rp::Params(); p.cropB = 0.5f;
        geo(src, W, H, p, out, ow, oh);
        auto rb = remap(4, 1, [&](int i, int j, int& sx, int& sy) { sx = i; sy = 0; });
        CHECK(ow == 4 && oh == 1 && out == plain(rb, 4, 1), "recorte inferior 50%: queda la fila superior");

        // Sin geometría el resultado es el del camino rápido.
        p = rp::Params();
        geo(src, W, H, p, out, ow, oh);
        CHECK(out == plain(src, W, H), "geometría neutra = revelado normal (muestreo exacto en el centro del píxel)");

        // Sentido del enderezado: con +90° la foto gira en sentido horario (centro-derecha -> centro-abajo).
        const int N = 9;
        std::vector<uint16_t> dot(N * N * 3, 6000);
        for (int c = 0; c < 3; ++c) dot[(4 * N + 8) * 3 + c] = 60000;  // píxel brillante a la derecha
        auto brightest = [&](const std::vector<uint8_t>& o) {
            int best = 0, bi = 0;
            for (int i = 0; i < N * N; ++i)
                if (o[i * 3] > best) { best = o[i * 3]; bi = i; }
            return std::pair<int, int>(bi % N, bi / N);
        };
        p = rp::Params(); p.angle = 90.f;
        geo(dot, N, N, p, out, ow, oh);
        auto bp = brightest(out);
        CHECK(bp.first == 4 && bp.second == 8, "ángulo +90°: el punto de la derecha baja (giro horario)");
        p.angle = -90.f;
        geo(dot, N, N, p, out, ow, oh);
        bp = brightest(out);
        CHECK(bp.first == 4 && bp.second == 0, "ángulo -90°: el punto de la derecha sube (giro antihorario)");
    }

    std::printf("[geometría] recorte automático de la app (valores calculados por EditParams.kt)\n");
    {
        // Foto gris con 1 px de borde rojo: si el recorte dejara esquinas vacías, el motor repetiría
        // el borde (rojo) y aparecerían muchos píxeles rojos en la imagen final.
        struct Case { int w, h; float deg, l, t, r, b; };
        const Case cases[] = {
            {128, 96, 20.f, 0.1418f, 0.1418f, 0.1418f, 0.1418f},   // Original
            {128, 96, 20.f, 0.2074f, 0.1099f, 0.2074f, 0.1099f},   // 1:1
            {96, 128, -33.f, 0.1805f, 0.1805f, 0.1805f, 0.1805f},  // Original, vertical
            {128, 96, 41.f, 0.153f, 0.2397f, 0.153f, 0.2397f},     // 16:9
            {128, 96, -7.f, 0.0344f, 0.0861f, 0.0344f, 0.0861f},   // 3:2
        };
        auto redCount = [](const Case& c, bool withCrop) {
            std::vector<uint16_t> img(static_cast<size_t>(c.w) * c.h * 3);
            for (int y = 0; y < c.h; ++y)
                for (int x = 0; x < c.w; ++x) {
                    bool border = x == 0 || y == 0 || x == c.w - 1 || y == c.h - 1;
                    uint16_t* px = &img[(static_cast<size_t>(y) * c.w + x) * 3];
                    px[0] = border ? 52000 : 13000;
                    px[1] = border ? 1300 : 13000;
                    px[2] = border ? 1300 : 13000;
                }
            rp::Params p;
            p.angle = c.deg;
            if (withCrop) { p.cropL = c.l; p.cropT = c.t; p.cropR = c.r; p.cropB = c.b; }
            int ow, oh;
            rp::outputSize(c.w, c.h, p, ow, oh);
            std::vector<uint8_t> out(static_cast<size_t>(ow) * oh * 3);
            rp::developGeometry(img.data(), c.w, c.h, p, out.data(), ow, oh, 1);
            int red = 0;
            for (int i = 0; i < ow * oh; ++i)
                if (out[i * 3] - out[i * 3 + 1] > 40) ++red;
            return red;
        };
        bool allClean = true, allDirtyWithout = true;
        int worstClean = 0, leastDirty = 1 << 30;
        for (const Case& c : cases) {
            int clean = redCount(c, true), dirty = redCount(c, false);
            worstClean = std::max(worstClean, clean);
            leastDirty = std::min(leastDirty, dirty);
            if (clean > 40) allClean = false;
            if (dirty < 150) allDirtyWithout = false;
        }
        std::printf("        (píxeles de borde: con recorte automático <= %d; sin recorte >= %d)\n", worstClean, leastDirty);
        CHECK(allClean, "con el recorte automático no se ve el borde de la foto (sin esquinas vacías)");
        CHECK(allDirtyWithout, "sin recorte, enderezar sí deja el borde repetido (la prueba puede fallar)");
    }

    std::printf("[curva y HSL]\n");
    {
        auto px = [](const rp::Params& p, float r, float g, float b, uint8_t* o) {
            rp::Developer(p).pixel(r, g, b, o);
        };
        uint8_t base[3], o[3];
        rp::Params none;
        px(none, 0.18f, 0.18f, 0.18f, base);

        rp::Params up; up.curve[2] = 0.2f;
        px(up, 0.18f, 0.18f, 0.18f, o);
        CHECK(o[0] > base[0] + 20 && o[0] == o[1] && o[1] == o[2], "curva: subir el punto medio aclara los medios");
        px(up, 0.f, 0.f, 0.f, o);
        CHECK(o[0] == 0, "curva: el negro sigue en 0");
        px(up, 1.f, 1.f, 1.f, o);
        CHECK(o[0] == 255, "curva: el blanco sigue en 255");
        rp::Params down; down.curve[2] = -0.2f;
        px(down, 0.18f, 0.18f, 0.18f, o);
        CHECK(o[0] < base[0] - 20, "curva: bajar el punto medio oscurece");

        rp::Params all;
        for (float& v : all.hsl) v = 1.f;
        uint8_t g1[3], g2[3];
        px(none, 0.3f, 0.3f, 0.3f, g1);
        px(all, 0.3f, 0.3f, 0.3f, g2);
        CHECK(g1[0] == g2[0] && g1[1] == g2[1] && g1[2] == g2[2], "HSL: un gris no cambia con ninguna banda");

        // Rojo (banda 0): saturación -1 lo deja gris; el verde (banda 3) no se toca.
        rp::Params red; red.hsl[0 * 3 + 1] = -1.f;
        uint8_t r0[3], r1[3], gr0[3], gr1[3];
        px(none, 0.5f, 0.05f, 0.05f, r0);
        px(red, 0.5f, 0.05f, 0.05f, r1);
        CHECK(r0[0] - r0[1] > 60, "rojo de prueba: saturado sin editar");
        CHECK(std::abs(r1[0] - r1[1]) <= 1 && std::abs(r1[1] - r1[2]) <= 1, "HSL: rojo con saturación -100 queda gris");
        px(none, 0.05f, 0.4f, 0.05f, gr0);
        px(red, 0.05f, 0.4f, 0.05f, gr1);
        CHECK(gr0[0] == gr1[0] && gr0[1] == gr1[1] && gr0[2] == gr1[2], "HSL: ajustar el rojo no toca al verde");

        // Tono: rojo +1 lo gira hacia naranja (sube el verde, no cambia el máximo).
        rp::Params hue; hue.hsl[0 * 3 + 0] = 1.f;
        uint8_t h1[3];
        px(hue, 0.5f, 0.05f, 0.05f, h1);
        CHECK(h1[1] > r0[1] + 30 && h1[0] >= r0[0] - 1, "HSL: tono +100 en rojo lo gira hacia naranja");

        // Luminosidad: el amarillo (banda 2) -1 lo oscurece, +1 lo aclara.
        rp::Params lm, ln; lm.hsl[2 * 3 + 2] = -1.f; ln.hsl[2 * 3 + 2] = 1.f;
        uint8_t y0[3], ym[3], yp[3];
        px(none, 0.4f, 0.4f, 0.02f, y0);
        px(lm, 0.4f, 0.4f, 0.02f, ym);
        px(ln, 0.4f, 0.4f, 0.02f, yp);
        CHECK(ym[0] < y0[0] - 20 && yp[0] > y0[0], "HSL: luminosidad del amarillo oscurece y aclara");
    }

    std::printf("[half-float] conversión para la textura GPU\n");
    {
        CHECK(rp::floatToHalf(0.f) == 0x0000 && rp::floatToHalf(1.f) == 0x3C00 &&
                  rp::floatToHalf(0.5f) == 0x3800 && rp::floatToHalf(-2.f) == 0xC000,
              "valores exactos (0, 1, 0.5, -2)");
        CHECK(rp::halfToFloat(0x3C00) == 1.f && rp::halfToFloat(0x0001) == std::ldexp(1.f, -24),
              "decodificación de 1.0 y del menor subnormal");
        // Todos los valores posibles de 16 bits: error relativo <= 2^-11 (o absoluto <= 2^-25 en subnormales).
        double worst = 0;
        bool monotonic = true;
        uint16_t prev = 0;
        for (int i = 0; i < 65536; ++i) {
            float f = static_cast<float>(i) / 65535.f;
            uint16_t hv = rp::floatToHalf(f);
            float back = rp::halfToFloat(hv);
            double tol = std::max(f * std::ldexp(1.0, -11), std::ldexp(1.0, -25));
            worst = std::max(worst, std::abs(back - f) / tol);
            if (hv < prev) monotonic = false;
            prev = hv;
        }
        CHECK(worst <= 1.0001, "error de redondeo dentro de media unidad en todo el rango 0..1");
        CHECK(monotonic, "la conversión es monótona");
        uint16_t src[6] = {0, 65535, 32768, 1, 2, 3};
        uint16_t dst[8];
        rp::rgb16ToRgba16f(src, 2, 1, dst);
        CHECK(dst[0] == 0 && dst[1] == 0x3C00 && dst[3] == 0x3C00 && dst[7] == 0x3C00,
              "RGB16 -> RGBA16F: canales y alfa = 1.0");
    }

    std::printf("[Developer] pixel por pixel\n");
    {
        rp::Params neutral;
        rp::Developer dev(neutral);
        uint8_t o[3];
        dev.pixel(0.18f, 0.18f, 0.18f, o);
        CHECK(std::abs(o[0] - 118) <= 1 && o[0] == o[1] && o[1] == o[2], "gris 18% lineal -> ~118 sRGB");
        dev.pixel(0, 0, 0, o);
        CHECK(o[0] == 0 && o[1] == 0 && o[2] == 0, "negro -> 0");
        dev.pixel(1, 1, 1, o);
        CHECK(o[0] == 255 && o[1] == 255 && o[2] == 255, "blanco -> 255");

        rp::Params up; up.exposure = 1.f;
        uint8_t o2[3];
        rp::Developer(up).pixel(0.18f, 0.18f, 0.18f, o2);
        CHECK(o2[0] > 118 + 20, "+1 EV aclara");

        rp::Params warm; warm.temperature = 0.5f;
        rp::Developer(warm).pixel(0.18f, 0.18f, 0.18f, o2);
        CHECK(o2[0] > o2[2], "temperatura cálida: R > B");

        rp::Params desat; desat.saturation = -1.f;
        rp::Developer(desat).pixel(0.5f, 0.1f, 0.05f, o2);
        CHECK(std::abs(o2[0] - o2[1]) <= 1 && std::abs(o2[1] - o2[2]) <= 1, "saturación -1 -> gris");

        rp::Params hl; hl.highlights = -1.f;
        uint8_t hi_a[3], hi_b[3];
        rp::Developer(neutral).pixel(0.8f, 0.8f, 0.8f, hi_a);
        rp::Developer(hl).pixel(0.8f, 0.8f, 0.8f, hi_b);
        CHECK(hi_b[0] < hi_a[0], "highlights -1 oscurece luces");

        rp::Params sh; sh.shadows = 1.f;
        uint8_t s_a[3], s_b[3];
        rp::Developer(neutral).pixel(0.02f, 0.02f, 0.02f, s_a);
        rp::Developer(sh).pixel(0.02f, 0.02f, 0.02f, s_b);
        CHECK(s_b[0] > s_a[0], "shadows +1 aclara sombras");
    }

    std::printf("[Session] abrir DNG sintético\n");
    std::unique_ptr<rp::Session> s;
    int rc = rp::openSession(a, 512, s);
    std::printf("  rc=%d (%s)\n", rc, rc ? rp::errorText(rc).c_str() : "ok");
    CHECK(rc == 0 && s, "openSession");
    if (rc != 0 || !s) return 1;
    std::printf("  info: %s  preview: %dx%d\n", s->info.c_str(), s->pw, s->ph);
    CHECK(s->info.rfind("RawPocket|Test|", 0) == 0, "marca y modelo leídos de los metadatos");
    CHECK(s->pw > 0 && s->ph > 0 && s->pw * 3 == s->ph * 4, "relación de aspecto 4:3 conservada");

    // Efecto de los controles sobre la vista previa real.
    auto render = [&](const rp::Params& p) {
        std::vector<uint8_t> px(static_cast<size_t>(s->pw) * s->ph * 4);
        rp::developToRgba(s->preview.data(), s->pw, s->ph, p, px.data(), s->pw * 4, 4);
        return px;
    };
    auto at = [&](const std::vector<uint8_t>& px, float u, float v) {
        int x = static_cast<int>(u * (s->pw - 1)), y = static_cast<int>(v * (s->ph - 1));
        return &px[(static_cast<size_t>(y) * s->pw + x) * 4];
    };
    rp::Params neutral;
    auto base = render(neutral);
    {
        const uint8_t* red = at(base, 0.15f, 0.5f);
        const uint8_t* green = at(base, 0.37f, 0.5f);
        const uint8_t* gray = at(base, 0.59f, 0.5f);
        const uint8_t* sky = at(base, 0.5f, 0.1f);
        const uint8_t* dark = at(base, 0.17f, 0.82f);
        const uint8_t* burnt = at(base, 0.82f, 0.82f);
        std::printf("  rojo=(%d,%d,%d) verde=(%d,%d,%d) gris=(%d,%d,%d) cielo=(%d,%d,%d) sombra=%d luz=%d\n",
                    red[0], red[1], red[2], green[0], green[1], green[2], gray[0], gray[1], gray[2],
                    sky[0], sky[1], sky[2], dark[0], burnt[0]);
        CHECK(red[0] > red[1] + 60 && red[0] > red[2] + 60, "parche rojo es rojo");
        CHECK(green[1] > green[0] + 60 && green[1] > green[2] + 60, "parche verde es verde");
        CHECK(std::abs(gray[0] - gray[1]) < 12 && std::abs(gray[1] - gray[2]) < 12, "parche gris es neutro");
        CHECK(sky[2] > sky[0] + 40, "cielo azulado");
        CHECK(dark[0] < 40, "sombra profunda oscura");
        CHECK(burnt[0] > 245, "luz quemada ~blanco");
    }
    {
        rp::Params p; p.exposure = 1.f;
        auto px = render(p);
        CHECK(luma(at(px, 0.59f, 0.5f)) > luma(at(base, 0.59f, 0.5f)) + 20, "exposición +1 sobre RAW real");
        p = rp::Params(); p.shadows = 1.f;
        px = render(p);
        CHECK(at(px, 0.17f, 0.82f)[0] > at(base, 0.17f, 0.82f)[0], "sombras +1 sobre RAW real");
        p = rp::Params(); p.saturation = -1.f;
        px = render(p);
        const uint8_t* r = at(px, 0.15f, 0.5f);
        CHECK(std::abs(r[0] - r[1]) <= 2 && std::abs(r[1] - r[2]) <= 2, "saturación -1 sobre RAW real");
    }

    std::printf("[Export] JPEG\n");
    rp::Params edit;
    edit.exposure = 0.3f; edit.contrast = 0.2f; edit.shadows = 0.4f; edit.highlights = -0.3f; edit.vibrance = 0.3f;
    std::string out1 = outdir + "/export_full.jpg";
    rc = rp::exportJpeg(*s, edit, out1, 92, 0);
    CHECK(rc == 0, "exportJpeg resolución completa");
    std::string out2 = outdir + "/export_128.jpg";
    rc = rp::exportJpeg(*s, rp::Params(), out2, 90, 128);
    CHECK(rc == 0, "exportJpeg limitado a 128 px");
    FILE* f = std::fopen(out1.c_str(), "rb");
    uint8_t sig[3] = {0, 0, 0};
    if (f) { if (std::fread(sig, 1, 3, f) != 3) {} std::fclose(f); }
    CHECK(sig[0] == 0xFF && sig[1] == 0xD8 && sig[2] == 0xFF, "el archivo es un JPEG válido (firma SOI)");

    // Exportación con geometría: el JPEG sale con el tamaño girado/recortado.
    {
        int fw = 0, fh = 0;
        bool ok = jpegSize(out1, fw, fh);
        CHECK(ok && fw > 0 && fh > 0, "se puede leer el tamaño del JPEG exportado");
        auto exportSize = [&](const rp::Params& p, int& w, int& h) {
            std::string path = outdir + "/export_geo.jpg";
            int rc2 = rp::exportJpeg(*s, p, path, 90, 0);
            return rc2 == 0 && jpegSize(path, w, h);
        };
        int w = 0, h = 0;
        rp::Params g; g.rotate90 = 1;
        CHECK(exportSize(g, w, h) && w == fh && h == fw, "exportar con giro de 90°: ancho y alto intercambiados");
        g = rp::Params(); g.cropL = 0.25f; g.cropR = 0.25f;
        CHECK(exportSize(g, w, h) && std::abs(w - fw / 2) <= 1 && h == fh, "exportar con recorte lateral del 50%: mitad de ancho");
        g = rp::Params(); g.rotate90 = 3; g.angle = 10.f; g.cropL = g.cropR = g.cropT = g.cropB = 0.1f; g.exposure = 0.3f;
        CHECK(exportSize(g, w, h) && std::abs(w - static_cast<int>(std::lround(0.8 * fh))) <= 1 &&
                  std::abs(h - static_cast<int>(std::lround(0.8 * fw))) <= 1,
              "exportar con giro + enderezado + recorte + tono: tamaño esperado");
    }

    // Vista previa revelada con la edición, para inspección visual.
    {
        std::vector<uint8_t> rgb(static_cast<size_t>(s->pw) * s->ph * 3);
        rp::developToRgb8(s->preview.data(), s->pw, s->ph, edit, rgb.data(), 2);
    }

    std::printf("[Orientación] EXIF 6 (90° horario)\n");
    std::unique_ptr<rp::Session> s6;
    rc = rp::openSession(a6, 512, s6);
    CHECK(rc == 0 && s6, "openSession con orientación 6");
    if (s6) {
        std::printf("  preview: %dx%d (normal: %dx%d)\n", s6->pw, s6->ph, s->pw, s->ph);
        CHECK(s6->pw == s->ph && s6->ph == s->pw, "las dimensiones se intercambian");
    }

    std::printf("[Errores]\n");
    std::unique_ptr<rp::Session> bad;
    rc = rp::openSession("/tmp/no_existe.dng", 512, bad);
    std::printf("  rc=%d: %s\n", rc, rp::errorText(rc).c_str());
    CHECK(rc != 0 && !bad, "archivo inexistente devuelve error");
    FILE* junk = std::fopen((outdir + "/junk.dng").c_str(), "wb");
    if (junk) { const char* t = "esto no es un raw, solo texto"; std::fwrite(t, 1, 29, junk); std::fclose(junk); }
    rc = rp::openSession(outdir + "/junk.dng", 512, bad);
    std::printf("  rc=%d: %s\n", rc, rp::errorText(rc).c_str());
    CHECK(rc != 0 && !bad, "archivo corrupto devuelve error");

    std::printf("\n%s (%d fallos)\n", failures ? "FALLÓ" : "TODO OK", failures);
    return failures ? 1 : 0;
}
