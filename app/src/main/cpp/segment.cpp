#include "segment.h"

#include <algorithm>
#include <cmath>
#include <cstdio>
#include <vector>

namespace rp {
namespace {

inline float smooth(float a, float b, float x) {
    float t = std::min(std::max((x - a) / (b - a), 0.f), 1.f);
    return t * t * (3.f - 2.f * t);
}

float srgbEncode(float x) {
    x = std::min(std::max(x, 0.f), 1.f);
    return x <= 0.0031308f ? 12.92f * x : 1.055f * std::pow(x, 1.f / 2.4f) - 0.055f;
}

// Muestra bilineal de un plano w x h en (fx, fy) en píxeles (centros en .5), bordes repetidos.
float bilinear(const float* p, int w, int h, float x, float y) {
    float fx = x - 0.5f, fy = y - 0.5f;
    float flx = std::floor(fx), fly = std::floor(fy);
    float tx = fx - flx, ty = fy - fly;
    int x0 = std::min(std::max(static_cast<int>(flx), 0), w - 1);
    int x1 = std::min(std::max(static_cast<int>(flx) + 1, 0), w - 1);
    int y0 = std::min(std::max(static_cast<int>(fly), 0), h - 1);
    int y1 = std::min(std::max(static_cast<int>(fly) + 1, 0), h - 1);
    float a = p[static_cast<size_t>(y0) * w + x0], b = p[static_cast<size_t>(y0) * w + x1];
    float c = p[static_cast<size_t>(y1) * w + x0], d = p[static_cast<size_t>(y1) * w + x1];
    return (a + (b - a) * tx) * (1.f - ty) + (c + (d - c) * tx) * ty;
}

// Promedio en una ventana de radio r (bordes repetidos), en el sitio.
void boxMean(std::vector<float>& d, int w, int h, int r) {
    if (r <= 0) return;
    const double inv = 1.0 / (2 * r + 1);
    std::vector<float> line(std::max(w, h));
    for (int y = 0; y < h; ++y) {
        float* row = &d[static_cast<size_t>(y) * w];
        std::copy(row, row + w, line.begin());
        double sum = static_cast<double>(r + 1) * line[0];
        for (int i = 1; i <= r; ++i) sum += line[std::min(i, w - 1)];
        for (int x = 0; x < w; ++x) {
            row[x] = static_cast<float>(sum * inv);
            sum += line[std::min(x + r + 1, w - 1)] - line[std::max(x - r, 0)];
        }
    }
    for (int x = 0; x < w; ++x) {
        for (int y = 0; y < h; ++y) line[y] = d[static_cast<size_t>(y) * w + x];
        double sum = static_cast<double>(r + 1) * line[0];
        for (int i = 1; i <= r; ++i) sum += line[std::min(i, h - 1)];
        for (int y = 0; y < h; ++y) {
            d[static_cast<size_t>(y) * w + x] = static_cast<float>(sum * inv);
            sum += line[std::min(y + r + 1, h - 1)] - line[std::max(y - r, 0)];
        }
    }
}

// Filtro guiado (He et al.): suaviza p respetando los bordes de la guía I.
void guidedFilter(const std::vector<float>& I, std::vector<float>& p, int w, int h, int r, float eps) {
    const size_t n = static_cast<size_t>(w) * h;
    std::vector<float> mI(I), mp(p), Ip(n), II(n);
    for (size_t i = 0; i < n; ++i) { Ip[i] = I[i] * p[i]; II[i] = I[i] * I[i]; }
    boxMean(mI, w, h, r); boxMean(mp, w, h, r); boxMean(Ip, w, h, r); boxMean(II, w, h, r);
    std::vector<float> a(n), b(n);
    for (size_t i = 0; i < n; ++i) {
        float varI = II[i] - mI[i] * mI[i];
        float cov = Ip[i] - mI[i] * mp[i];
        a[i] = cov / (varI + eps);
        b[i] = mp[i] - a[i] * mI[i];
    }
    boxMean(a, w, h, r); boxMean(b, w, h, r);
    for (size_t i = 0; i < n; ++i) p[i] = a[i] * I[i] + b[i];
}

}  // namespace

void segmentInput(const uint16_t* rgb16, int w, int h, float* out) {
    // Imagen codificada en sRGB, reducida a 320x320 (como hacen las herramientas de recorte de
    // fondo con este modelo: sin conservar la proporción) con promedio de área.
    const int N = kSegSize;
    std::vector<float> img(static_cast<size_t>(N) * N * 3);
    for (int y = 0; y < N; ++y) {
        int ys0 = y * h / N, ys1 = std::max(ys0 + 1, (y + 1) * h / N);
        for (int x = 0; x < N; ++x) {
            int xs0 = x * w / N, xs1 = std::max(xs0 + 1, (x + 1) * w / N);
            double acc[3] = {0, 0, 0};
            int cnt = 0;
            for (int yy = ys0; yy < ys1 && yy < h; ++yy)
                for (int xx = xs0; xx < xs1 && xx < w; ++xx, ++cnt) {
                    const uint16_t* s = rgb16 + (static_cast<size_t>(yy) * w + xx) * 3;
                    acc[0] += s[0]; acc[1] += s[1]; acc[2] += s[2];
                }
            if (cnt == 0) cnt = 1;
            for (int c = 0; c < 3; ++c)
                img[(static_cast<size_t>(y) * N + x) * 3 + c] =
                    srgbEncode(static_cast<float>(acc[c] / cnt) * (1.f / 65535.f));
        }
    }
    float mx = 1e-6f;
    for (float v : img) mx = std::max(mx, v);
    static const float mean[3] = {0.485f, 0.456f, 0.406f};
    static const float stdv[3] = {0.229f, 0.224f, 0.225f};
    for (int c = 0; c < 3; ++c)
        for (int y = 0; y < N; ++y)
            for (int x = 0; x < N; ++x)
                out[(static_cast<size_t>(c) * N + y) * N + x] =
                    (img[(static_cast<size_t>(y) * N + x) * 3 + c] / mx - mean[c]) / stdv[c];
}

void segmentFinish(const uint16_t* rgb16, int w, int h, const float* pred, int maxEdge,
                   SubjectMask& out) {
    const int N = kSegSize;
    float lo = pred[0], hi = pred[0];
    for (int i = 1; i < N * N; ++i) { lo = std::min(lo, pred[i]); hi = std::max(hi, pred[i]); }
    std::vector<float> norm(static_cast<size_t>(N) * N);
    float span = hi - lo > 1e-6f ? hi - lo : 1.f;
    for (int i = 0; i < N * N; ++i) norm[i] = (pred[i] - lo) / span;

    float sc = std::min(1.f, static_cast<float>(std::max(16, maxEdge)) / static_cast<float>(std::max(w, h)));
    const int mw = std::max(1, static_cast<int>(std::lround(w * sc)));
    const int mh = std::max(1, static_cast<int>(std::lround(h * sc)));
    const size_t n = static_cast<size_t>(mw) * mh;

    // Predicción ampliada y guía (luminancia codificada de la propia foto) a la resolución de la máscara.
    std::vector<float> p(n), guide(n);
    for (int y = 0; y < mh; ++y) {
        for (int x = 0; x < mw; ++x) {
            size_t i = static_cast<size_t>(y) * mw + x;
            p[i] = bilinear(norm.data(), N, N, (x + 0.5f) * N / mw, (y + 0.5f) * N / mh);
            float sx = (x + 0.5f) * w / mw, sy = (y + 0.5f) * h / mh;
            int x0 = std::min(static_cast<int>(sx), w - 1), y0 = std::min(static_cast<int>(sy), h - 1);
            const uint16_t* s = rgb16 + (static_cast<size_t>(y0) * w + x0) * 3;
            float lum = (0.2126f * s[0] + 0.7152f * s[1] + 0.0722f * s[2]) * (1.f / 65535.f);
            guide[i] = srgbEncode(lum);
        }
    }
    int r = std::max(3, static_cast<int>(std::lround(std::max(mw, mh) * 0.006f)));
    guidedFilter(guide, p, mw, mh, r, 2e-3f);

    out.w = mw;
    out.h = mh;
    out.a.resize(n);
    for (size_t i = 0; i < n; ++i)
        out.a[i] = static_cast<uint8_t>(smooth(0.15f, 0.85f, p[i]) * 255.f + 0.5f);
}

int saveSubjectMask(const SubjectMask& m, const std::string& path) {
    if (m.empty()) return -1;
    FILE* f = std::fopen(path.c_str(), "wb");
    if (!f) return -2;
    int32_t hd[2] = {m.w, m.h};
    bool ok = std::fwrite(hd, sizeof(int32_t), 2, f) == 2 &&
              std::fwrite(m.a.data(), 1, static_cast<size_t>(m.w) * m.h, f) == static_cast<size_t>(m.w) * m.h;
    std::fclose(f);
    return ok ? 0 : -2;
}

int loadSubjectMask(SubjectMask& m, const std::string& path) {
    FILE* f = std::fopen(path.c_str(), "rb");
    if (!f) return -1;
    int32_t hd[2] = {0, 0};
    int rc = -3;
    if (std::fread(hd, sizeof(int32_t), 2, f) == 2 && hd[0] > 0 && hd[1] > 0 && hd[0] <= 8192 && hd[1] <= 8192) {
        SubjectMask t;
        t.w = hd[0];
        t.h = hd[1];
        t.a.resize(static_cast<size_t>(t.w) * t.h);
        if (std::fread(t.a.data(), 1, t.a.size(), f) == t.a.size()) {
            m = std::move(t);
            rc = 0;
        }
    }
    std::fclose(f);
    return rc;
}

}  // namespace rp
