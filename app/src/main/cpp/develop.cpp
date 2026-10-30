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
    for (int i = 0; i < Params::kCurvePoints; ++i) p.curve[i] = a[10 + i];
    for (int i = 0; i < Params::kHslBands * 3; ++i) p.hsl[i] = a[10 + Params::kCurvePoints + i];
    const float* g = a + Params::kGeomStart;
    p.angle = g[0];
    p.rotate90 = g[1];
    p.cropL = g[2];
    p.cropT = g[3];
    p.cropR = g[4];
    p.cropB = g[5];
    const float* f = a + Params::kFxStart;
    p.clarity = f[0];
    p.texture = f[1];
    p.dehaze = f[2];
    p.sharpen = f[3];
    p.nrLuma = f[4];
    p.nrColor = f[5];
    p.vigAmount = f[6];
    p.vigMid = f[7];
    p.grain = f[8];
    for (int z = 0; z < 3; ++z) {
        p.gradeHue[z] = f[9 + z * 2];
        p.gradeSat[z] = f[10 + z * 2];
    }
    p.gradeBalance = f[15];
    return p;
}

Developer::Developer(const Params& p) : p_(p) {
    float ev = std::exp2(p.exposure);
    // Temperatura/matiz: ajustes relativos sobre el balance de blancos de la cámara.
    gain_[0] = ev * (1.f + 0.35f * p.temperature);
    gain_[1] = ev * (1.f - 0.25f * p.tint);
    gain_[2] = ev * (1.f - 0.35f * p.temperature);
    hlk_ = std::max(0.f, -p.highlights) * 3.f;

    // Curva: interpolación cúbica de Hermite con tangentes monótonas (sin sobreimpulsos).
    float sum = 0.f;
    for (int i = 0; i < Params::kCurvePoints; ++i) {
        curveY_[i] = clamp01(0.25f * i + p.curve[i]);
        sum += std::fabs(p.curve[i]);
    }
    curveActive_ = sum > 0.f;
    float d[4];
    for (int i = 0; i < 4; ++i) d[i] = (curveY_[i + 1] - curveY_[i]) / 0.25f;
    curveM_[0] = d[0];
    curveM_[4] = d[3];
    for (int i = 1; i < 4; ++i)
        curveM_[i] = (d[i - 1] * d[i] <= 0.f) ? 0.f : 2.f * d[i - 1] * d[i] / (d[i - 1] + d[i]);

    float hs = 0.f;
    for (float v : p.hsl) hs += std::fabs(v);
    hslActive_ = hs > 0.f;

    spatial_ = p.hasSpatial();
    grade_ = p.gradeSat[0] > 0.f || p.gradeSat[1] > 0.f || p.gradeSat[2] > 0.f;
}

void Developer::setOutput(int outW, int outH) {
    outW_ = std::max(1, outW);
    outH_ = std::max(1, outH);
    // El grano tiene una cuadrícula de como máximo 2048 px de lado largo: se ve igual en la vista
    // previa y en la exportación a resolución completa.
    float s = std::min(1.f, 2048.f / static_cast<float>(std::max(outW_, outH_)));
    gridW_ = std::max(1, static_cast<int>(std::lround(outW_ * s)));
    gridH_ = std::max(1, static_cast<int>(std::lround(outH_ * s)));
}

float Developer::curveAt(float x) const {
    float xs = x * 4.f;
    int i = std::min(3, static_cast<int>(std::floor(xs)));
    float t = xs - static_cast<float>(i), t2 = t * t, t3 = t2 * t;
    float r = (2.f * t3 - 3.f * t2 + 1.f) * curveY_[i] + (t3 - 2.f * t2 + t) * 0.25f * curveM_[i] +
              (-2.f * t3 + 3.f * t2) * curveY_[i + 1] + (t3 - t2) * 0.25f * curveM_[i + 1];
    return clamp01(r);
}

namespace {

inline float mod6(float x) { return x - 6.f * std::floor(x / 6.f); }

// Centros de las 8 bandas (grados de tono); el último es el rojo otra vez (360).
constexpr float kBandCenter[9] = {0.f, 30.f, 60.f, 120.f, 180.f, 240.f, 270.f, 300.f, 360.f};

}  // namespace

// HSL por color: los pesos de banda se interpolan linealmente entre centros contiguos.
// Los grises no cambian (el tono no está definido y las ganancias se escalan con la saturación).
void Developer::applyHsl(float c[3]) const {
    float mx = std::max(c[0], std::max(c[1], c[2]));
    float mn = std::min(c[0], std::min(c[1], c[2]));
    float d = mx - mn;
    if (d <= 0.f || mx <= 0.f) return;

    float s0 = d / mx, v = mx, h;
    if (mx == c[0]) h = (c[1] - c[2]) / d;
    else if (mx == c[1]) h = (c[2] - c[0]) / d + 2.f;
    else h = (c[0] - c[1]) / d + 4.f;
    h = mod6(h) * 60.f;

    int k = 0;
    for (int i = 1; i < 8; ++i)
        if (h >= kBandCenter[i]) k = i;
    float t = (h - kBandCenter[k]) / (kBandCenter[k + 1] - kBandCenter[k]);
    int a = k, b = (k + 1) % 8;
    auto mixBand = [&](int ch) { return (1.f - t) * p_.hsl[a * 3 + ch] + t * p_.hsl[b * 3 + ch]; };
    float dh = mixBand(0), ds = mixBand(1), dl = mixBand(2);

    h += dh * 30.f;
    float s = clamp01(s0 * (1.f + ds));
    v = clamp01(v * (1.f + 0.5f * dl * s0));

    float h6 = mod6(h / 60.f);
    constexpr float K[3] = {0.f, 4.f, 2.f};
    for (int i = 0; i < 3; ++i) {
        float q = clamp01(std::fabs(mod6(h6 + K[i]) - 3.f) - 1.f);
        c[i] = v * (1.f - s + s * q);
    }
}

namespace {

inline float lum(const float c[3]) { return 0.2126f * c[0] + 0.7152f * c[1] + 0.0722f * c[2]; }

// Color puro de un tono (grados), igual que el color de salida de applyHsl con s = v = 1.
void hueRgb(float deg, float out[3]) {
    float h6 = mod6(deg / 60.f);
    constexpr float K[3] = {0.f, 4.f, 2.f};
    for (int i = 0; i < 3; ++i) out[i] = clamp01(std::fabs(mod6(h6 + K[i]) - 3.f) - 1.f);
}

// Ruido de cuadrícula en [-1, 1], determinista (mismo resultado en CPU y GPU).
float gridNoise(int x, int y) {
    uint32_t h = static_cast<uint32_t>(x) * 374761393u + static_cast<uint32_t>(y) * 668265263u;
    h = (h ^ (h >> 13)) * 1274126177u;
    h ^= h >> 16;
    return static_cast<float>(h & 0xFFFFu) * (1.f / 32767.5f) - 1.f;
}

}  // namespace

void Developer::pixel(float r, float g, float b, const float* aux, float u, float v,
                      uint8_t* out) const {
    static const float* lut = encodeLut();

    float c[3] = {r, g, b};

    // Efectos con vecinos (sobre la imagen lineal, antes de exposición y tonos).
    if (spatial_ && aux) {
        const float* N = aux;  // imagen con desenfoque pequeño
        const float Lc = aux[3], Lm = aux[4], Hz = aux[5];
        if (p_.dehaze != 0.f) {
            constexpr float A = 0.95f;  // luz ambiente supuesta
            if (p_.dehaze > 0.f) {
                float t = std::min(1.f, std::max(0.25f, 1.f - 0.9f * p_.dehaze * (Hz / A)));
                for (float& x : c) x = (x - A) / t + A;
            } else {
                float k = -p_.dehaze * 0.5f;
                for (float& x : c) x = x + (A - x) * k;
            }
            for (float& x : c) x = std::max(x, 0.f);
        }
        const float Yn = lum(N);
        if (p_.nrColor > 0.f) {  // el color se acerca al de la versión desenfocada
            float Y = lum(c);
            for (int i = 0; i < 3; ++i) c[i] += p_.nrColor * ((N[i] - Yn) - (c[i] - Y));
        }
        if (p_.nrLuma > 0.f) {  // la luminancia también, salvo en los bordes
            float Y = lum(c);
            float d = std::fabs(std::sqrt(std::max(Y, 0.f)) - std::sqrt(std::max(Yn, 0.f)));
            float w = p_.nrLuma * (1.f - smoothstep(0.02f, 0.12f, d));
            float dy = w * (Yn - Y);
            for (float& x : c) x += dy;
        }
        if (p_.sharpen > 0.f || p_.texture != 0.f || p_.clarity != 0.f) {
            float Y = std::max(lum(c), 0.f);
            float s = std::sqrt(Y), sS = std::sqrt(std::max(Yn, 0.f));
            float sT = std::sqrt(std::max(Lm, 0.f)), sC = std::sqrt(std::max(Lc, 0.f));
            float wC = std::max(1.f - (2.f * sC - 1.f) * (2.f * sC - 1.f), 0.f);  // más en medios tonos
            float s2 = s + p_.sharpen * 0.9f * (s - sS) + p_.texture * 0.9f * (s - sT) +
                       p_.clarity * 1.2f * wC * (s - sC);
            s2 = std::max(s2, 0.f);
            float sc = std::min(((s2 * s2) + 0.002f) / (Y + 0.002f), 8.f);
            for (float& x : c) x *= sc;
        }
    }

    for (int i = 0; i < 3; ++i) c[i] *= gain_[i];

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
    if (curveActive_) y2 = curveAt(y2);

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

    for (float& v : c) v = clamp01(v);
    if (hslActive_) applyHsl(c);

    // Gradación de color: un tinte distinto en sombras, medios y luces.
    if (grade_) {
        float y = lum(c);
        float bal = p_.gradeBalance * 0.25f;
        float wS = 1.f - smoothstep(0.10f, 0.55f + bal, y);
        float wH = smoothstep(0.45f + bal, 0.90f, y);
        float wM = clamp01(1.f - wS - wH);
        const float zone[3] = {wS, wM, wH};
        for (int z = 0; z < 3; ++z) {
            if (p_.gradeSat[z] <= 0.f) continue;
            float tint[3];
            hueRgb(p_.gradeHue[z], tint);
            float tl = lum(tint);
            float k = p_.gradeSat[z] * zone[z] * 0.30f;
            for (int i = 0; i < 3; ++i) c[i] += (tint[i] - tl) * k;
        }
        for (float& x : c) x = clamp01(x);
    }

    // Viñeta: oscurece (o aclara) hacia las esquinas de la imagen final.
    if (p_.vigAmount != 0.f) {
        float aspect = static_cast<float>(outW_) / static_cast<float>(outH_);
        float dx = (u - 0.5f) * aspect, dy = v - 0.5f;
        float d = std::sqrt(dx * dx + dy * dy) / (0.5f * std::sqrt(aspect * aspect + 1.f));
        float t = smoothstep(0.45f + 0.4f * p_.vigMid, 1.f, d);
        if (p_.vigAmount < 0.f) {
            for (float& x : c) x *= 1.f + p_.vigAmount * t;
        } else {
            for (float& x : c) x += (1.f - x) * p_.vigAmount * t;
        }
    }

    // Grano: más visible en medios tonos que en negros y blancos.
    if (p_.grain > 0.f) {
        int gx = static_cast<int>(std::floor(u * static_cast<float>(gridW_)));
        int gy = static_cast<int>(std::floor(v * static_cast<float>(gridH_)));
        float y = lum(c);
        float amp = p_.grain * 0.10f * (0.4f + 1.6f * y * (1.f - y));
        float n = gridNoise(gx, gy) * amp;
        for (float& x : c) x += n;
    }

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

namespace {

// Tabla half -> float (65536 entradas): los efectos leen muchos valores half por píxel.
const float* halfTable() {
    static const std::vector<float> t = [] {
        std::vector<float> v(65536);
        for (int i = 0; i < 65536; ++i) v[i] = halfToFloat(static_cast<uint16_t>(i));
        return v;
    }();
    return t.data();
}

// Muestra las capas auxiliares en (xs, ys) (0..1 sobre la fuente) con la misma bilineal que la
// fuente (y que sampleAux() en develop.frag). out: a.rgba, b.rg.
void auxSample(const Aux& aux, float xs, float ys, float out[6]) {
    const float* tab = halfTable();
    const int w = aux.w, h = aux.h;
    float fx = xs * static_cast<float>(w) - 0.5f, fy = ys * static_cast<float>(h) - 0.5f;
    float flx = std::floor(fx), fly = std::floor(fy);
    float tx = fx - flx, ty = fy - fly;
    tx = tx < 1e-4f ? 0.f : (tx > 0.9999f ? 1.f : tx);
    ty = ty < 1e-4f ? 0.f : (ty > 0.9999f ? 1.f : ty);
    int x0 = std::min(std::max(static_cast<int>(flx), 0), w - 1);
    int x1 = std::min(std::max(static_cast<int>(flx) + 1, 0), w - 1);
    int y0 = std::min(std::max(static_cast<int>(fly), 0), h - 1);
    int y1 = std::min(std::max(static_cast<int>(fly) + 1, 0), h - 1);
    auto idx = [&](int x, int y) { return static_cast<size_t>(y) * w + x; };
    for (int ch = 0; ch < 4; ++ch) {
        float a = tab[aux.a[idx(x0, y0) * 4 + ch]], b = tab[aux.a[idx(x1, y0) * 4 + ch]];
        float c = tab[aux.a[idx(x0, y1) * 4 + ch]], e = tab[aux.a[idx(x1, y1) * 4 + ch]];
        float top = a + (b - a) * tx, bottom = c + (e - c) * tx;
        out[ch] = top + (bottom - top) * ty;
    }
    for (int ch = 0; ch < 2; ++ch) {
        float a = tab[aux.b[idx(x0, y0) * 2 + ch]], b = tab[aux.b[idx(x1, y0) * 2 + ch]];
        float c = tab[aux.b[idx(x0, y1) * 2 + ch]], e = tab[aux.b[idx(x1, y1) * 2 + ch]];
        float top = a + (b - a) * tx, bottom = c + (e - c) * tx;
        out[4 + ch] = top + (bottom - top) * ty;
    }
}

}  // namespace

void developToRgba(const uint16_t* src, int w, int h, const Params& p,
                   uint8_t* dst, int dstStride, int threads, const Aux* aux) {
    Developer dev(p);
    dev.setOutput(w, h);
    const Aux* ax = (aux && !aux->empty()) ? aux : nullptr;
    constexpr float inv = 1.f / 65535.f;
    parallelRows(h, threads, [&](int y0, int y1) {
        for (int y = y0; y < y1; ++y) {
            const uint16_t* s = src + static_cast<size_t>(y) * w * 3;
            uint8_t* d = dst + static_cast<size_t>(y) * dstStride;
            for (int x = 0; x < w; ++x, s += 3, d += 4) {
                float u = (static_cast<float>(x) + 0.5f) / static_cast<float>(w);
                float v = (static_cast<float>(y) + 0.5f) / static_cast<float>(h);
                float av[6];
                if (ax) auxSample(*ax, u, v, av);
                dev.pixel(s[0] * inv, s[1] * inv, s[2] * inv, ax ? av : nullptr, u, v, d);
                d[3] = 255;
            }
        }
    });
}

namespace {

// Geometría resuelta: la misma matemática que sourceUv() en develop.frag.
struct Geo {
    float ca, sa;       // coseno y seno del enderezado
    int k;              // giros de 90° (0..3)
    float l, t, r, b;   // recorte
    float wA, hA;       // tamaño del marco girado (px)
    float W, H;         // tamaño de la fuente (px)

    Geo(int srcW, int srcH, const Params& p) {
        constexpr float kDegToRad = 0.017453292519943295f;
        ca = std::cos(p.angle * kDegToRad);
        sa = std::sin(p.angle * kDegToRad);
        k = static_cast<int>(std::floor(p.rotate90 + 0.5f)) & 3;
        l = clamp01(p.cropL);
        t = clamp01(p.cropT);
        r = clamp01(p.cropR);
        b = clamp01(p.cropB);
        W = static_cast<float>(srcW);
        H = static_cast<float>(srcH);
        wA = (k & 1) ? H : W;
        hA = (k & 1) ? W : H;
    }

    // (u, v): posición en la imagen final (0..1, y hacia abajo) -> posición en la fuente (0..1).
    void sourceUv(float u, float v, float& xs, float& ys) const {
        float ax = l + (1.f - r - l) * u;
        float ay = t + (1.f - b - t) * v;
        float cx = 0.5f * wA, cy = 0.5f * hA;
        float dx = ax * wA - cx, dy = ay * hA - cy;
        float sx = (cx + ca * dx + sa * dy) / wA;
        float sy = (cy - sa * dx + ca * dy) / hA;
        switch (k) {
            case 1: xs = sy; ys = 1.f - sx; break;
            case 2: xs = 1.f - sx; ys = 1.f - sy; break;
            case 3: xs = 1.f - sy; ys = sx; break;
            default: xs = sx; ys = sy; break;
        }
    }
};

inline float lerp(float a, float b, float t) { return a + (b - a) * t; }

}  // namespace

void outputSize(int srcW, int srcH, const Params& p, int& outW, int& outH) {
    Geo g(srcW, srcH, p);
    outW = std::max(1, static_cast<int>(std::lround((1.f - g.l - g.r) * g.wA)));
    outH = std::max(1, static_cast<int>(std::lround((1.f - g.t - g.b) * g.hA)));
}

void developGeometry(const uint16_t* src, int w, int h, const Params& p,
                     uint8_t* dst, int outW, int outH, int threads, const Aux* aux) {
    Developer dev(p);
    dev.setOutput(outW, outH);
    const Aux* ax = (aux && !aux->empty()) ? aux : nullptr;
    Geo g(w, h, p);
    constexpr float inv = 1.f / 65535.f;
    parallelRows(outH, threads, [&](int y0, int y1) {
        for (int oy = y0; oy < y1; ++oy) {
            uint8_t* d = dst + static_cast<size_t>(oy) * outW * 3;
            float v = (static_cast<float>(oy) + 0.5f) / static_cast<float>(outH);
            for (int ox = 0; ox < outW; ++ox, d += 3) {
                float u = (static_cast<float>(ox) + 0.5f) / static_cast<float>(outW);
                float xs, ys;
                g.sourceUv(u, v, xs, ys);
                // Bilineal con los bordes repetidos (igual que CLAMP_TO_EDGE en la GPU).
                float fx = xs * g.W - 0.5f, fy = ys * g.H - 0.5f;
                float flx = std::floor(fx), fly = std::floor(fy);
                float tx = fx - flx, ty = fy - fly;
                // Igual que sampleBilinear() en develop.frag: dentro de 1e-4 px del centro de un
                // texel se ajusta a él (el redondeo no debe mezclar vecinos brillantes en un negro).
                tx = tx < 1e-4f ? 0.f : (tx > 0.9999f ? 1.f : tx);
                ty = ty < 1e-4f ? 0.f : (ty > 0.9999f ? 1.f : ty);
                int x0 = std::min(std::max(static_cast<int>(flx), 0), w - 1);
                int x1 = std::min(std::max(static_cast<int>(flx) + 1, 0), w - 1);
                int y0i = std::min(std::max(static_cast<int>(fly), 0), h - 1);
                int y1i = std::min(std::max(static_cast<int>(fly) + 1, 0), h - 1);
                const uint16_t* a = src + (static_cast<size_t>(y0i) * w + x0) * 3;
                const uint16_t* b = src + (static_cast<size_t>(y0i) * w + x1) * 3;
                const uint16_t* c = src + (static_cast<size_t>(y1i) * w + x0) * 3;
                const uint16_t* e = src + (static_cast<size_t>(y1i) * w + x1) * 3;
                float rgb[3];
                for (int ch = 0; ch < 3; ++ch)
                    rgb[ch] = lerp(lerp(a[ch], b[ch], tx), lerp(c[ch], e[ch], tx), ty) * inv;
                float av[6];
                if (ax) auxSample(*ax, xs, ys, av);
                dev.pixel(rgb[0], rgb[1], rgb[2], ax ? av : nullptr, u, v, d);
            }
        }
    });
}

void developToRgb8(const uint16_t* src, int w, int h, const Params& p,
                   uint8_t* dst, int threads, const Aux* aux) {
    Developer dev(p);
    dev.setOutput(w, h);
    const Aux* ax = (aux && !aux->empty()) ? aux : nullptr;
    constexpr float inv = 1.f / 65535.f;
    parallelRows(h, threads, [&](int y0, int y1) {
        for (int y = y0; y < y1; ++y) {
            const uint16_t* s = src + static_cast<size_t>(y) * w * 3;
            uint8_t* d = dst + static_cast<size_t>(y) * w * 3;
            for (int x = 0; x < w; ++x, s += 3, d += 3) {
                float u = (static_cast<float>(x) + 0.5f) / static_cast<float>(w);
                float v = (static_cast<float>(y) + 0.5f) / static_cast<float>(h);
                float av[6];
                if (ax) auxSample(*ax, u, v, av);
                dev.pixel(s[0] * inv, s[1] * inv, s[2] * inv, ax ? av : nullptr, u, v, d);
            }
        }
    });
}

namespace {

// Desenfoque de caja con bordes repetidos y radio r, sobre un plano w*h (en el sitio).
void boxBlurPlane(std::vector<float>& d, int w, int h, int r) {
    if (r <= 0) return;
    const double inv = 1.0 / (2 * r + 1);
    // Horizontal.
    for (int y = 0; y < h; ++y) {
        float* row = &d[static_cast<size_t>(y) * w];
        std::vector<float> src(row, row + w);
        double sum = static_cast<double>(r + 1) * src[0];
        for (int i = 1; i <= r; ++i) sum += src[std::min(i, w - 1)];
        for (int x = 0; x < w; ++x) {
            row[x] = static_cast<float>(sum * inv);
            sum += src[std::min(x + r + 1, w - 1)] - src[std::max(x - r, 0)];
        }
    }
    // Vertical, fila a fila (acceso secuencial a memoria).
    std::vector<float> src(d);
    std::vector<double> col(w);
    for (int x = 0; x < w; ++x) {
        double sum = static_cast<double>(r + 1) * src[x];
        for (int i = 1; i <= r; ++i) sum += src[static_cast<size_t>(std::min(i, h - 1)) * w + x];
        col[x] = sum;
    }
    for (int y = 0; y < h; ++y) {
        float* row = &d[static_cast<size_t>(y) * w];
        for (int x = 0; x < w; ++x) row[x] = static_cast<float>(col[x] * inv);
        const float* add = &src[static_cast<size_t>(std::min(y + r + 1, h - 1)) * w];
        const float* sub = &src[static_cast<size_t>(std::max(y - r, 0)) * w];
        for (int x = 0; x < w; ++x) col[x] += add[x] - sub[x];
    }
}

// Dos pasadas de caja: una aproximación suave de un desenfoque gaussiano.
void softBlur(std::vector<float>& d, int w, int h, int r) {
    boxBlurPlane(d, w, h, r);
    boxBlurPlane(d, w, h, r);
}

}  // namespace

void buildAux(const uint16_t* src, int w, int h, int step, Aux& out) {
    std::vector<uint16_t> reduced;
    int bw = w, bh = h;
    if (step > 1) {
        boxDownsample16(src, w, h, step, reduced, bw, bh);
        src = reduced.data();
    }
    const size_t n = static_cast<size_t>(bw) * bh;
    const int longEdge = std::max(bw, bh);
    auto radius = [&](float frac, int minimum) {
        return std::max(minimum, static_cast<int>(std::lround(longEdge * frac)));
    };

    std::vector<float> R(n), G(n), B(n), Y(n), M(n);
    constexpr float inv = 1.f / 65535.f;
    for (size_t i = 0; i < n; ++i) {
        float r = src[i * 3] * inv, g = src[i * 3 + 1] * inv, b = src[i * 3 + 2] * inv;
        R[i] = r; G[i] = g; B[i] = b;
        Y[i] = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        M[i] = std::min(r, std::min(g, b));
    }
    std::vector<float> Lm(Y), Lc(Y);
    softBlur(R, bw, bh, radius(0.0008f, 1));
    softBlur(G, bw, bh, radius(0.0008f, 1));
    softBlur(B, bw, bh, radius(0.0008f, 1));
    softBlur(Lm, bw, bh, radius(0.004f, 2));
    softBlur(Lc, bw, bh, radius(0.02f, 4));
    softBlur(M, bw, bh, radius(0.03f, 4));

    out.w = bw;
    out.h = bh;
    out.a.resize(n * 4);
    out.b.resize(n * 2);
    for (size_t i = 0; i < n; ++i) {
        out.a[i * 4] = floatToHalf(R[i]);
        out.a[i * 4 + 1] = floatToHalf(G[i]);
        out.a[i * 4 + 2] = floatToHalf(B[i]);
        out.a[i * 4 + 3] = floatToHalf(Lc[i]);
        out.b[i * 2] = floatToHalf(Lm[i]);
        out.b[i * 2 + 1] = floatToHalf(M[i]);
    }
}

}  // namespace rp
