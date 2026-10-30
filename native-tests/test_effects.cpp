// Pruebas de los efectos nuevos del motor CPU: detalle (con vecinos), viñeta, grano y gradación.
#include <cmath>
#include <cstdio>
#include <vector>

#include "develop.h"

static int fails = 0;
#define CHECK(c) do { if (!(c)) { std::printf("  FALLA línea %d: %s\n", __LINE__, #c); ++fails; } } while (0)

static uint32_t rng = 12345;
static float frand() { rng = rng * 1664525u + 1013904223u; return (rng >> 8) / 16777216.f; }

static std::vector<uint16_t> flat(int w, int h, float v) {
    std::vector<uint16_t> s(static_cast<size_t>(w) * h * 3, static_cast<uint16_t>(v * 65535.f));
    return s;
}
static std::vector<uint8_t> develop(const std::vector<uint16_t>& s, int w, int h, const rp::Params& p, bool useAux = true) {
    rp::Aux aux;
    if (useAux) rp::buildAux(s.data(), w, h, 1, aux);
    std::vector<uint8_t> out(static_cast<size_t>(w) * h * 3);
    rp::developToRgb8(s.data(), w, h, p, out.data(), 1, useAux ? &aux : nullptr);
    return out;
}
static double stddev(const std::vector<uint8_t>& o, int w, int h, int x0, int y0, int x1, int y1) {
    double sum = 0, sq = 0; long n = 0;
    for (int y = y0; y < y1; ++y) for (int x = x0; x < x1; ++x) { double v = o[(static_cast<size_t>(y) * w + x) * 3 + 1]; sum += v; sq += v * v; ++n; }
    double m = sum / n; return std::sqrt(std::max(0.0, sq / n - m * m));
}
static double mean(const std::vector<uint8_t>& o, int w, int x0, int y0, int x1, int y1, int ch = 1) {
    double sum = 0; long n = 0;
    for (int y = y0; y < y1; ++y) for (int x = x0; x < x1; ++x) { sum += o[(static_cast<size_t>(y) * w + x) * 3 + ch]; ++n; }
    return sum / n;
}

int main() {
    const int W = 160, H = 120;

    std::printf("[capas auxiliares]\n");
    {
        auto s = flat(W, H, 0.3f);
        rp::Aux a;
        rp::buildAux(s.data(), W, H, 1, a);
        CHECK(a.w == W && a.h == H && a.a.size() == size_t(W) * H * 4 && a.b.size() == size_t(W) * H * 2);
        // imagen constante -> todas las capas son constantes (un desenfoque no cambia nada)
        float c = rp::halfToFloat(a.a[(H / 2 * W + W / 2) * 4]);
        CHECK(std::fabs(c - 0.3f) < 0.002f);
        CHECK(std::fabs(rp::halfToFloat(a.a[(5 * W + 5) * 4 + 3]) - 0.3f) < 0.002f);  // también en el borde
        CHECK(std::fabs(rp::halfToFloat(a.b[(H - 1) * W * 2 + 1]) - 0.3f) < 0.002f);
        rp::Aux half;
        rp::buildAux(s.data(), W, H, 2, half);
        CHECK(half.w == W / 2 && half.h == H / 2);
    }

    std::printf("[sin efectos no cambia nada]\n");
    {
        std::vector<uint16_t> s(W * H * 3);
        for (auto& v : s) v = static_cast<uint16_t>(frand() * 60000);
        rp::Params p; p.exposure = 0.3f;
        CHECK(develop(s, W, H, p, true) == develop(s, W, H, p, false));
        CHECK(!p.hasSpatial());
    }

    std::printf("[reducción de ruido]\n");
    {
        std::vector<uint16_t> s(W * H * 3);
        for (size_t i = 0; i < s.size(); i += 3) {
            float base = 0.25f, n = (frand() - 0.5f) * 0.12f;
            s[i] = static_cast<uint16_t>((base + n) * 65535); s[i + 1] = static_cast<uint16_t>((base + n * 0.9f) * 65535);
            s[i + 2] = static_cast<uint16_t>((base + (frand() - 0.5f) * 0.12f) * 65535);
        }
        rp::Params p0;
        double before = stddev(develop(s, W, H, p0), W, H, 20, 20, 140, 100);
        rp::Params p; p.nrLuma = 1.f;
        double after = stddev(develop(s, W, H, p), W, H, 20, 20, 140, 100);
        std::printf("  ruido de luminancia: %.2f -> %.2f\n", before, after);
        CHECK(after < before * 0.7);
        // el ruido de color: la diferencia R-B baja
        auto chromaSpread = [&](const std::vector<uint8_t>& o) {
            double acc = 0; long n = 0;
            for (int y = 20; y < 100; ++y) for (int x = 20; x < 140; ++x) { size_t i = (size_t(y) * W + x) * 3; acc += std::abs(o[i] - o[i + 2]); ++n; }
            return acc / n;
        };
        rp::Params pc; pc.nrColor = 1.f;
        double cb = chromaSpread(develop(s, W, H, p0)), ca = chromaSpread(develop(s, W, H, pc));
        std::printf("  ruido de color: %.2f -> %.2f\n", cb, ca);
        CHECK(ca < cb * 0.7);
    }

    std::printf("[nitidez, textura y claridad aumentan el contraste local]\n");
    {
        // borde vertical suave entre 0.2 y 0.5
        std::vector<uint16_t> s(W * H * 3);
        for (int y = 0; y < H; ++y) for (int x = 0; x < W; ++x) {
            float v = x < W / 2 ? 0.2f : 0.5f;
            for (int c = 0; c < 3; ++c) s[(size_t(y) * W + x) * 3 + c] = static_cast<uint16_t>(v * 65535);
        }
        auto base = develop(s, W, H, rp::Params());
        auto span = [&](const std::vector<uint8_t>& o, int xa, int xb) { return mean(o, W, xb, 40, xb + 1, 80) - mean(o, W, xa, 40, xa + 1, 80); };
        // diferencia entre justo a ambos lados del borde, y lejos del borde
        for (int which = 0; which < 3; ++which) {
            rp::Params p;
            if (which == 0) p.sharpen = 1.f;
            if (which == 1) p.texture = 0.8f;
            if (which == 2) p.clarity = 0.8f;
            auto o = develop(s, W, H, p);
            double near = span(o, W / 2 - 1, W / 2), nearBase = span(base, W / 2 - 1, W / 2);
            std::printf("  %s: contraste en el borde %.1f -> %.1f\n", which == 0 ? "nitidez" : which == 1 ? "textura" : "claridad", nearBase, near);
            CHECK(near > nearBase + 2);
        }
        rp::Params neg; neg.clarity = -0.8f;
        auto o = develop(s, W, H, neg);
        CHECK(span(o, W / 2 - 1, W / 2) < span(base, W / 2 - 1, W / 2));
    }

    std::printf("[neblina]\n");
    {
        // imagen "con neblina": poco contraste, base elevada
        std::vector<uint16_t> s(W * H * 3);
        for (size_t i = 0; i < s.size(); ++i) s[i] = static_cast<uint16_t>((0.45f + 0.15f * ((i / 3) % W) / W) * 65535);
        rp::Params p; p.dehaze = 0.8f;
        auto o = develop(s, W, H, p), b = develop(s, W, H, rp::Params());
        CHECK(mean(o, W, 10, 10, 30, 30) < mean(b, W, 10, 10, 30, 30) - 5);  // más oscuro
        rp::Params add; add.dehaze = -0.8f;
        CHECK(mean(develop(s, W, H, add), W, 10, 10, 30, 30) > mean(b, W, 10, 10, 30, 30) + 3);
    }

    std::printf("[viñeta]\n");
    {
        auto s = flat(W, H, 0.4f);
        rp::Params p; p.vigAmount = -1.f;
        auto o = develop(s, W, H, p), b = develop(s, W, H, rp::Params());
        CHECK(mean(o, W, 0, 0, 6, 6) < mean(b, W, 0, 0, 6, 6) - 30);          // esquina oscura
        CHECK(std::fabs(mean(o, W, W / 2 - 3, H / 2 - 3, W / 2 + 3, H / 2 + 3) - mean(b, W, 0, 0, 3, 3)) < 1.5);  // centro intacto
        rp::Params light; light.vigAmount = 1.f;
        CHECK(mean(develop(s, W, H, light), W, 0, 0, 6, 6) > mean(b, W, 0, 0, 6, 6) + 30);
        // el punto medio mueve el inicio de la caída
        rp::Params wide = p; wide.vigMid = 1.f;
        CHECK(mean(develop(s, W, H, wide), W, W / 4, H / 4, W / 4 + 4, H / 4 + 4) > mean(o, W, W / 4, H / 4, W / 4 + 4, H / 4 + 4));
        // la viñeta sigue a la imagen final (recorte): las esquinas del recorte se oscurecen
        rp::Params crop = p; crop.cropL = 0.3f;
        int ow, oh; rp::outputSize(W, H, crop, ow, oh);
        std::vector<uint8_t> g(size_t(ow) * oh * 3);
        rp::developGeometry(s.data(), W, H, crop, g.data(), ow, oh, 1);
        CHECK(mean(g, ow, 0, 0, 4, 4) < mean(g, ow, ow / 2 - 2, oh / 2 - 2, ow / 2 + 2, oh / 2 + 2) - 25);
    }

    std::printf("[grano]\n");
    {
        auto s = flat(W, H, 0.3f);
        rp::Params p; p.grain = 1.f;
        auto o = develop(s, W, H, p), o2 = develop(s, W, H, p);
        CHECK(o == o2);  // determinista
        CHECK(stddev(o, W, H, 10, 10, 150, 110) > 3.0);
        rp::Params half = p; half.grain = 0.5f;
        CHECK(stddev(develop(s, W, H, half), W, H, 10, 10, 150, 110) < stddev(o, W, H, 10, 10, 150, 110));
        CHECK(std::fabs(mean(o, W, 10, 10, 150, 110) - mean(develop(s, W, H, rp::Params()), W, 10, 10, 150, 110)) < 2.0);  // sin sesgo
        // más grano en medios tonos que en negros casi puros
        auto dark = flat(W, H, 0.002f);
        CHECK(stddev(develop(dark, W, H, p), W, H, 10, 10, 150, 110) < stddev(o, W, H, 10, 10, 150, 110));
    }

    std::printf("[gradación de color]\n");
    {
        // degradado de negro a blanco
        std::vector<uint16_t> s(W * H * 3);
        for (int y = 0; y < H; ++y) for (int x = 0; x < W; ++x)
            for (int c = 0; c < 3; ++c) s[(size_t(y) * W + x) * 3 + c] = static_cast<uint16_t>(std::pow(x / float(W - 1), 2.2f) * 65535);
        rp::Params p; p.gradeHue[0] = 220.f; p.gradeSat[0] = 1.f;   // sombras azules
        p.gradeHue[2] = 40.f;  p.gradeSat[2] = 1.f;                 // luces naranjas
        auto o = develop(s, W, H, p);
        // en sombras (x ~ 25%): azul > rojo; en luces (x ~ 90%): rojo > azul
        CHECK(mean(o, W, W / 4, 20, W / 4 + 2, 100, 2) > mean(o, W, W / 4, 20, W / 4 + 2, 100, 0) + 4);
        CHECK(mean(o, W, int(W * 0.9), 20, int(W * 0.9) + 2, 100, 0) > mean(o, W, int(W * 0.9), 20, int(W * 0.9) + 2, 100, 2) + 4);
        rp::Params none = p; none.gradeSat[0] = none.gradeSat[2] = 0.f;
        auto n = develop(s, W, H, none);
        CHECK(std::abs(mean(n, W, 40, 20, 42, 100, 0) - mean(n, W, 40, 20, 42, 100, 2)) < 1.0);  // sin saturación, neutro
    }
    std::printf(fails ? "FALLÓ (%d)\n" : "OK\n", fails);
    return fails ? 1 : 0;
}
