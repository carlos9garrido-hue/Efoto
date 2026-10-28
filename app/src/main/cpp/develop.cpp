#include "develop.h"

#include <algorithm>
#include <cmath>
#include <cstring>
#include <thread>

namespace rp {

namespace {

constexpr int kLutSize = 16384;

inline float clamp01(float x) { return x < 0.f ? 0.f : (x > 1.f ? 1.f : x); }

inline float smoothstep(float a, float b, float x) {
    float t = clamp01((x - a) / (b - a));
    return t * t * (3.f - 2.f * t);
}

// Tabla lineal -> sRGB (codificación). Se interpola para evitar bandas en sombras.
const float* encodeLut() {
    static const std::vector<float> lut = [] {
        std::vector<float> t(kLutSize + 1);
        for (int i = 0; i <= kLutSize; ++i) {
            float x = static_cast<float>(i) / kLutSize;
            t[i] = x <= 0.0031308f ? 12.92f * x : 1.055f * std::pow(x, 1.f / 2.4f) - 0.055f;
        }
        return t;
    }();
    return lut.data();
}

inline float encode(const float* lut, float x) {
    x = clamp01(x) * kLutSize;
    int i = static_cast<int>(x);
    if (i >= kLutSize) return lut[kLutSize];
    float f = x - i;
    return lut[i] + (lut[i + 1] - lut[i]) * f;
}

template <typename Fn>
void parallelRows(int h, int threads, Fn fn) {
    threads = std::max(1, std::min(threads, h));
    if (threads == 1) {
        fn(0, h);
        return;
    }
    std::vector<std::thread> pool;
    pool.reserve(threads);
    int per = (h + threads - 1) / threads;
    for (int t = 0; t < threads; ++t) {
        int y0 = t * per, y1 = std::min(h, y0 + per);
        if (y0 >= y1) break;
        pool.emplace_back([=] { fn(y0, y1); });
    }
    for (auto& th : pool) th.join();
}

}  // namespace

Params Params::fromArray(const float* a) {
    Params p;
    p.exposure = a[0];
    p.contrast = a[1];
    p.highlights = a[2];
    p.shadows = a[3];
    p.whites = a[4];
    p.blacks = a[5];
    p.temperature = a[6];
    p.tint = a[7];
    p.vibrance = a[8];
    p.saturation = a[9];
    return p;
}

Developer::Developer(const Params& p) : p_(p) {
    float ev = std::exp2(p.exposure);
    // Temperatura/matiz: ajustes relativos sobre el balance de blancos de la cámara.
    gain_[0] = ev * (1.f + 0.35f * p.temperature);
    gain_[1] = ev * (1.f - 0.25f * p.tint);
    gain_[2] = ev * (1.f - 0.35f * p.temperature);
    hlk_ = std::max(0.f, -p.highlights) * 3.f;
}

void Developer::pixel(float r, float g, float b, uint8_t* out) const {
    static const float* lut = encodeLut();

    float c[3] = {r * gain_[0], g * gain_[1], b * gain_[2]};

    // Recuperación de altas luces en lineal: compresión suave sobre la rodilla.
    if (hlk_ > 0.f) {
        constexpr float knee = 0.6f;
        for (float& v : c) {
            if (v > knee) {
                float t = v - knee;
                v = knee + t / (1.f + hlk_ * t);
            }
        }
    }

    for (float& v : c) v = encode(lut, v);

    // Tonos por luminancia en espacio codificado.
    float y = 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2];
    float y2 = y;
    y2 += p_.shadows * 0.30f * (1.f - smoothstep(0.10f, 0.65f, y)) * smoothstep(0.f, 0.08f, y);
    y2 += p_.highlights * 0.25f * smoothstep(0.35f, 0.90f, y);
    y2 += p_.whites * 0.20f * smoothstep(0.60f, 1.00f, y);
    y2 += p_.blacks * 0.20f * (1.f - smoothstep(0.f, 0.35f, y));
    y2 = 0.5f + (y2 - 0.5f) * (1.f + 0.8f * p_.contrast);
    y2 = clamp01(y2);

    constexpr float eps = 0.002f;
    float scale = (y2 + eps) / (y + eps);
    for (float& v : c) v *= scale;

    // Vibrance (protege colores ya saturados) y saturación global.
    float mx = std::max(c[0], std::max(c[1], c[2]));
    float mn = std::min(c[0], std::min(c[1], c[2]));
    float chroma = clamp01(mx - mn);
    float sat = (1.f + p_.saturation) * (1.f + p_.vibrance * (1.f - chroma));
    if (sat < 0.f) sat = 0.f;
    for (float& v : c) v = y2 + (v - y2) * sat;

    for (int i = 0; i < 3; ++i) out[i] = static_cast<uint8_t>(clamp01(c[i]) * 255.f + 0.5f);
}

void boxDownsample16(const uint16_t* src, int w, int h, int step,
                     std::vector<uint16_t>& out, int& outW, int& outH) {
    if (step <= 1) {
        outW = w;
        outH = h;
        out.assign(src, src + static_cast<size_t>(w) * h * 3);
        return;
    }
    outW = w / step;
    outH = h / step;
    out.resize(static_cast<size_t>(outW) * outH * 3);
    const uint32_t area = static_cast<uint32_t>(step) * step;
    for (int y = 0; y < outH; ++y) {
        for (int x = 0; x < outW; ++x) {
            uint32_t acc[3] = {0, 0, 0};
            for (int dy = 0; dy < step; ++dy) {
                const uint16_t* row = src + (static_cast<size_t>(y) * step + dy) * w * 3 +
                                      static_cast<size_t>(x) * step * 3;
                for (int dx = 0; dx < step; ++dx) {
                    acc[0] += row[dx * 3];
                    acc[1] += row[dx * 3 + 1];
                    acc[2] += row[dx * 3 + 2];
                }
            }
            uint16_t* o = &out[(static_cast<size_t>(y) * outW + x) * 3];
            o[0] = static_cast<uint16_t>(acc[0] / area);
            o[1] = static_cast<uint16_t>(acc[1] / area);
            o[2] = static_cast<uint16_t>(acc[2] / area);
        }
    }
}

uint16_t floatToHalf(float f) {
    uint32_t x;
    std::memcpy(&x, &f, sizeof(x));
    const uint32_t sign = (x >> 16) & 0x8000u;
    const int32_t exp = static_cast<int32_t>((x >> 23) & 0xFFu) - 127 + 15;
    uint32_t mant = x & 0x7FFFFFu;

    if (((x >> 23) & 0xFFu) == 0xFFu)  // inf / NaN
        return static_cast<uint16_t>(sign | 0x7C00u | (mant ? 0x200u : 0u));
    if (exp >= 31) return static_cast<uint16_t>(sign | 0x7C00u);  // desborda a inf
    if (exp <= 0) {                                               // subnormal o cero
        if (exp < -10) return static_cast<uint16_t>(sign);
        mant |= 0x800000u;
        const int shift = 14 - exp;
        uint32_t half = mant >> shift;
        const uint32_t rem = mant & ((1u << shift) - 1u), mid = 1u << (shift - 1);
        if (rem > mid || (rem == mid && (half & 1u))) ++half;  // redondeo al par más cercano
        return static_cast<uint16_t>(sign | half);
    }
    uint32_t half = (static_cast<uint32_t>(exp) << 10) | (mant >> 13);
    const uint32_t rem = mant & 0x1FFFu;
    if (rem > 0x1000u || (rem == 0x1000u && (half & 1u))) ++half;  // puede propagar al exponente
    return static_cast<uint16_t>(sign | half);
}

float halfToFloat(uint16_t h) {
    const uint32_t sign = (h & 0x8000u) << 16;
    const uint32_t exp = (h >> 10) & 0x1Fu, mant = h & 0x3FFu;
    uint32_t out;
    if (exp == 0) {
        if (mant == 0) {
            out = sign;
        } else {  // subnormal -> normaliza
            int e = -1;
            uint32_t m = mant;
            do { ++e; m <<= 1; } while (!(m & 0x400u));
            out = sign | (static_cast<uint32_t>(127 - 15 - e) << 23) | ((m & 0x3FFu) << 13);
        }
    } else if (exp == 31) {
        out = sign | 0x7F800000u | (mant << 13);
    } else {
        out = sign | ((exp - 15 + 127) << 23) | (mant << 13);
    }
    float f;
    std::memcpy(&f, &out, sizeof(f));
    return f;
}

void rgb16ToRgba16f(const uint16_t* src, int w, int h, uint16_t* dst) {
    // Solo hay 65536 valores posibles: una tabla evita convertir cada canal.
    static const std::vector<uint16_t> table = [] {
        std::vector<uint16_t> t(65536);
        for (int i = 0; i < 65536; ++i) t[i] = floatToHalf(static_cast<float>(i) / 65535.f);
        return t;
    }();
    const uint16_t one = floatToHalf(1.f);
    const size_t n = static_cast<size_t>(w) * h;
    for (size_t i = 0; i < n; ++i, src += 3, dst += 4) {
        dst[0] = table[src[0]];
        dst[1] = table[src[1]];
        dst[2] = table[src[2]];
        dst[3] = one;
    }
}

void developToRgba(const uint16_t* src, int w, int h, const Params& p,
                   uint8_t* dst, int dstStride, int threads) {
    Developer dev(p);
    constexpr float inv = 1.f / 65535.f;
    parallelRows(h, threads, [&](int y0, int y1) {
        for (int y = y0; y < y1; ++y) {
            const uint16_t* s = src + static_cast<size_t>(y) * w * 3;
            uint8_t* d = dst + static_cast<size_t>(y) * dstStride;
            for (int x = 0; x < w; ++x, s += 3, d += 4) {
                dev.pixel(s[0] * inv, s[1] * inv, s[2] * inv, d);
                d[3] = 255;
            }
        }
    });
}

void developToRgb8(const uint16_t* src, int w, int h, const Params& p,
                   uint8_t* dst, int threads) {
    Developer dev(p);
    constexpr float inv = 1.f / 65535.f;
    parallelRows(h, threads, [&](int y0, int y1) {
        for (int y = y0; y < y1; ++y) {
            const uint16_t* s = src + static_cast<size_t>(y) * w * 3;
            uint8_t* d = dst + static_cast<size_t>(y) * w * 3;
            for (int x = 0; x < w; ++x, s += 3, d += 3)
                dev.pixel(s[0] * inv, s[1] * inv, s[2] * inv, d);
        }
    });
}

}  // namespace rp
