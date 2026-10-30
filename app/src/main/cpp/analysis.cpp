#include "analysis.h"

#include <algorithm>
#include <cmath>
#include <vector>

namespace rp {

namespace {

// Revela con geometría si hace falta. Devuelve RGB8 y su tamaño.
void developPreview(const uint16_t* src, int w, int h, const Params& p, std::vector<uint8_t>& rgb,
                    int& ow, int& oh) {
    if (p.hasGeometry()) {
        outputSize(w, h, p, ow, oh);
        rgb.assign(static_cast<size_t>(ow) * oh * 3, 0);
        developGeometry(src, w, h, p, rgb.data(), ow, oh, 1);
    } else {
        ow = w;
        oh = h;
        rgb.assign(static_cast<size_t>(w) * h * 3, 0);
        developToRgb8(src, w, h, p, rgb.data(), 1);
    }
}

inline int luma8(const uint8_t* p) {
    return static_cast<int>((2126 * p[0] + 7152 * p[1] + 722 * p[2] + 5000) / 10000);
}

float srgbToLinear(float c) { return c <= 0.04045f ? c / 12.92f : std::pow((c + 0.055f) / 1.055f, 2.4f); }

// Valor (0..255) por debajo del cual está la fracción q de los píxeles.
int percentile(const int32_t* hist256, long total, double q) {
    long target = static_cast<long>(std::ceil(q * total));
    long acc = 0;
    for (int i = 0; i < 256; ++i) {
        acc += hist256[i];
        if (acc >= target && acc > 0) return i;
    }
    return 255;
}

}  // namespace

void histogram8(const uint16_t* src, int w, int h, const Params& p, int32_t out[1024]) {
    std::fill(out, out + 1024, 0);
    std::vector<uint8_t> rgb;
    int ow = 0, oh = 0;
    developPreview(src, w, h, p, rgb, ow, oh);
    const size_t n = static_cast<size_t>(ow) * oh;
    for (size_t i = 0; i < n; ++i) {
        const uint8_t* px = &rgb[i * 3];
        ++out[px[0]];
        ++out[256 + px[1]];
        ++out[512 + px[2]];
        ++out[768 + luma8(px)];
    }
}

void autoAdjust(const uint16_t* src, int w, int h, float out[6]) {
    std::fill(out, out + 6, 0.f);
    const long total = static_cast<long>(w) * h;
    if (total <= 0) return;

    auto lumaHist = [&](const Params& p, int32_t hist[1024]) { histogram8(src, w, h, p, hist); };

    int32_t hist[1024];
    Params base;
    lumaHist(base, hist);
    const int median = percentile(hist + 768, total, 0.5);
    // Mediana lineal actual -> exposición que la lleva al gris medio (18 %).
    const float medLin = std::max(srgbToLinear(median / 255.f), 1.0f / 4096.f);
    float ev = std::log2(0.18f / medLin) * 0.85f;
    ev = std::max(-3.f, std::min(3.f, ev));
    // Si ya está bien expuesta no se toca.
    if (std::fabs(ev) < 0.1f) ev = 0.f;

    Params withEv;
    withEv.exposure = ev;
    lumaHist(withEv, hist);
    const int p005 = percentile(hist + 768, total, 0.005);
    const int p05 = percentile(hist + 768, total, 0.05);
    const int p95 = percentile(hist + 768, total, 0.95);
    const int p995 = percentile(hist + 768, total, 0.995);
    long bright = 0, dark = 0;
    for (int i = 240; i < 256; ++i) bright += hist[768 + i];
    for (int i = 0; i <= 30; ++i) dark += hist[768 + i];
    const float fBright = static_cast<float>(bright) / total;
    const float fDark = static_cast<float>(dark) / total;

    auto clampf = [](float v, float lo, float hi) { return std::max(lo, std::min(hi, v)); };
    out[0] = ev;
    out[1] = clampf((150.f - (p95 - p05)) / 300.f, 0.f, 0.3f);
    out[2] = -clampf(fBright * 3.f, 0.f, 0.6f);
    out[3] = clampf(fDark * 1.5f, 0.f, 0.6f);
    out[4] = clampf((240.f - p995) / 100.f, -0.3f, 0.5f);
    out[5] = p005 > 8 ? -clampf((p005 - 8.f) / 60.f, 0.f, 0.5f) : 0.f;
}

bool whiteBalanceFromRgb(float r, float g, float b, float& temperature, float& tint) {
    const float eps = 1e-4f;
    if (r < eps && g < eps && b < eps) return false;
    r = std::max(r, eps);
    g = std::max(g, eps);
    b = std::max(b, eps);
    // Ganancias de Developer: R = 1 + .35 t, B = 1 - .35 t, G = 1 - .25 tint.
    float t = (b - r) / (0.35f * (r + b));
    t = std::max(-1.f, std::min(1.f, t));
    const float gainR = 1.f + 0.35f * t;
    const float target = r * gainR;
    const float gainG = target / g;
    float ti = (1.f - gainG) / 0.25f;
    ti = std::max(-1.f, std::min(1.f, ti));
    temperature = t;
    tint = ti;
    return true;
}

bool samplePatchLinear(const uint16_t* src, int w, int h, const Params& p, float u, float v,
                       float rgb[3]) {
    if (u < 0.f || u > 1.f || v < 0.f || v > 1.f || w <= 0 || h <= 0) return false;
    Params g;  // solo geometría
    g.angle = p.angle;
    g.rotate90 = p.rotate90;
    g.cropL = p.cropL;
    g.cropT = p.cropT;
    g.cropR = p.cropR;
    g.cropB = p.cropB;
    std::vector<uint8_t> img;
    int ow = 0, oh = 0;
    developPreview(src, w, h, g, img, ow, oh);
    const int cx = std::min(ow - 1, std::max(0, static_cast<int>(u * ow)));
    const int cy = std::min(oh - 1, std::max(0, static_cast<int>(v * oh)));
    double acc[3] = {0, 0, 0};
    int n = 0;
    for (int dy = -2; dy <= 2; ++dy)
        for (int dx = -2; dx <= 2; ++dx) {
            int x = cx + dx, y = cy + dy;
            if (x < 0 || y < 0 || x >= ow || y >= oh) continue;
            const uint8_t* px = &img[(static_cast<size_t>(y) * ow + x) * 3];
            for (int c = 0; c < 3; ++c) acc[c] += srgbToLinear(px[c] / 255.f);
            ++n;
        }
    if (n == 0) return false;
    for (int c = 0; c < 3; ++c) rgb[c] = static_cast<float>(acc[c] / n);
    return true;
}

}  // namespace rp
