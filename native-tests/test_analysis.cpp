// Pruebas de histograma, ajuste automático y cuentagotas de balance de blancos.
#include <cmath>
#include <cstdio>
#include <vector>

#include "analysis.h"

static int fails = 0;
#define CHECK(c) do { if (!(c)) { std::printf("  FALLA línea %d: %s\n", __LINE__, #c); ++fails; } } while (0)

// Escena con degradado de luminancia entre lo y hi (lineal 0..1) y algo de textura.
static std::vector<uint16_t> scene(int w, int h, float lo, float hi) {
    std::vector<uint16_t> s(static_cast<size_t>(w) * h * 3);
    for (int y = 0; y < h; ++y)
        for (int x = 0; x < w; ++x) {
            float t = (x + 0.5f * (y % 7)) / (w + 4.f);
            float v = lo + (hi - lo) * t * t;
            for (int c = 0; c < 3; ++c)
                s[(static_cast<size_t>(y) * w + x) * 3 + c] = static_cast<uint16_t>(std::fmin(1.f, v * (0.95f + 0.05f * c)) * 65535.f);
        }
    return s;
}
static int medianAfter(const std::vector<uint16_t>& s, int w, int h, const rp::Params& p) {
    int32_t hist[1024];
    rp::histogram8(s.data(), w, h, p, hist);
    long acc = 0, total = static_cast<long>(w) * h;
    for (int i = 0; i < 256; ++i) { acc += hist[768 + i]; if (acc * 2 >= total) return i; }
    return 255;
}

int main() {
    const int W = 128, H = 96;

    std::printf("[histograma]\n");
    {
        auto s = scene(W, H, 0.02f, 0.9f);
        int32_t hist[1024];
        rp::histogram8(s.data(), W, H, rp::Params(), hist);
        for (int c = 0; c < 4; ++c) {
            long sum = 0;
            for (int i = 0; i < 256; ++i) sum += hist[c * 256 + i];
            CHECK(sum == W * H);
        }
        // con giro de 90° el total se conserva y con recorte baja
        rp::Params r; r.rotate90 = 1;
        rp::histogram8(s.data(), W, H, r, hist);
        long sum = 0; for (int i = 0; i < 256; ++i) sum += hist[768 + i];
        CHECK(sum == W * H);
        rp::Params cr; cr.cropL = 0.5f;
        rp::histogram8(s.data(), W, H, cr, hist);
        sum = 0; for (int i = 0; i < 256; ++i) sum += hist[768 + i];
        CHECK(sum == (W / 2) * H);
        // negro puro: todo en el bin 0
        std::vector<uint16_t> black(W * H * 3, 0);
        rp::histogram8(black.data(), W, H, rp::Params(), hist);
        CHECK(hist[0] == W * H && hist[768] == W * H);
    }

    std::printf("[auto]\n");
    {
        struct Case { const char* name; float lo, hi; };
        Case cases[] = {{"subexpuesta", 0.002f, 0.12f}, {"sobreexpuesta", 0.30f, 1.0f}, {"normal", 0.01f, 0.85f}};
        for (auto& c : cases) {
            auto s = scene(W, H, c.lo, c.hi);
            float a[6];
            rp::autoAdjust(s.data(), W, H, a);
            rp::Params p;
            p.exposure = a[0]; p.contrast = a[1]; p.highlights = a[2];
            p.shadows = a[3]; p.whites = a[4]; p.blacks = a[5];
            int before = medianAfter(s, W, H, rp::Params());
            int after = medianAfter(s, W, H, p);
            std::printf("  %-14s EV %+.2f ctr %+.2f hl %+.2f sh %+.2f wh %+.2f bl %+.2f  mediana %d -> %d\n",
                        c.name, a[0], a[1], a[2], a[3], a[4], a[5], before, after);
            for (int i = 0; i < 6; ++i) CHECK(std::isfinite(a[i]) && std::fabs(a[i]) <= 3.01f);
            CHECK(std::abs(after - 118) <= std::abs(before - 118) + 2);
            CHECK(std::abs(after - 118) <= 45);
        }
        float a[6];
        auto good = scene(W, H, 0.02f, 0.5f);
        rp::autoAdjust(good.data(), W, H, a);
        CHECK(std::fabs(a[0]) <= 1.2f);
        std::vector<uint16_t> black(W * H * 3, 0);
        rp::autoAdjust(black.data(), W, H, a);  // no debe dar NaN ni explotar
        for (int i = 0; i < 6; ++i) CHECK(std::isfinite(a[i]));
    }

    std::printf("[balance de blancos]\n");
    {
        // ida y vuelta: un parche que las ganancias de temp/matiz vuelven neutro
        for (float t0 : {-0.8f, -0.3f, 0.f, 0.4f, 0.9f})
            for (float ti0 : {-0.7f, 0.f, 0.5f}) {
                float gr = 1 + 0.35f * t0, gg = 1 - 0.25f * ti0, gb = 1 - 0.35f * t0;
                float t, ti;
                CHECK(rp::whiteBalanceFromRgb(0.4f / gr, 0.4f / gg, 0.4f / gb, t, ti));
                CHECK(std::fabs(t - t0) < 1e-4f && std::fabs(ti - ti0) < 1e-4f);
            }
        float t, ti;
        CHECK(!rp::whiteBalanceFromRgb(0, 0, 0, t, ti));
        CHECK(rp::whiteBalanceFromRgb(1, 0.001f, 0.001f, t, ti) && std::fabs(t) <= 1 && std::fabs(ti) <= 1);

        // muestreo con geometría: mitad izquierda gris, mitad derecha azulada; tras girar 180°
        // el punto (0.25, 0.5) de la imagen girada cae en la mitad azul de la original
        std::vector<uint16_t> s(W * H * 3);
        for (int y = 0; y < H; ++y)
            for (int x = 0; x < W; ++x) {
                bool right = x >= W / 2;
                uint16_t* p = &s[(static_cast<size_t>(y) * W + x) * 3];
                p[0] = right ? 12000 : 30000; p[1] = right ? 20000 : 30000; p[2] = right ? 40000 : 30000;
            }
        float rgb[3];
        CHECK(rp::samplePatchLinear(s.data(), W, H, rp::Params(), 0.25f, 0.5f, rgb));
        CHECK(std::fabs(rgb[0] - rgb[2]) < 0.01f);  // gris
        rp::Params r; r.rotate90 = 2;
        CHECK(rp::samplePatchLinear(s.data(), W, H, r, 0.25f, 0.5f, rgb));
        CHECK(rgb[2] > rgb[0] * 2.f);  // azul
        // un gris neutro da temperatura ~0 y matiz ~0
        rp::Params none;
        CHECK(rp::samplePatchLinear(s.data(), W, H, none, 0.1f, 0.5f, rgb) &&
              rp::whiteBalanceFromRgb(rgb[0], rgb[1], rgb[2], t, ti) && std::fabs(t) < 0.02f && std::fabs(ti) < 0.02f);
        CHECK(!rp::samplePatchLinear(s.data(), W, H, none, 1.5f, 0.5f, rgb));
    }
    std::printf(fails ? "FALLÓ (%d)\n" : "OK\n", fails);
    return fails ? 1 : 0;
}
