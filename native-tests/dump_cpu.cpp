// Herramienta de la prueba de paridad CPU/GPU.
// Uso: dump_cpu <in.rgb16> <w> <h> <out.rgb8> <out.half> p0 p1 ... p9
//   in.rgb16 : RGB lineal de 16 bits little-endian (w*h*3 valores)
//   out.rgb8 : resultado del motor CPU (lo que exporta la app)
//   out.half : la misma entrada convertida a RGBA half-float (lo que la app sube a la GPU)
#include <cstdio>
#include <cstdlib>
#include <vector>

#include "develop.h"

int main(int argc, char** argv) {
    if (argc != 6 + rp::Params::kCount) {
        std::fprintf(stderr, "uso: %s in.rgb16 w h out.rgb8 out.half p0..p9\n", argv[0]);
        return 2;
    }
    const int w = std::atoi(argv[2]), h = std::atoi(argv[3]);
    std::vector<uint16_t> in(static_cast<size_t>(w) * h * 3);
    FILE* f = std::fopen(argv[1], "rb");
    if (!f || std::fread(in.data(), 2, in.size(), f) != in.size()) {
        std::fprintf(stderr, "no se pudo leer %s\n", argv[1]);
        return 1;
    }
    std::fclose(f);

    float a[rp::Params::kCount];
    for (int i = 0; i < rp::Params::kCount; ++i) a[i] = static_cast<float>(std::atof(argv[6 + i]));

    std::vector<uint8_t> out(in.size());
    rp::developToRgb8(in.data(), w, h, rp::Params::fromArray(a), out.data(), 1);
    std::vector<uint16_t> half(static_cast<size_t>(w) * h * 4);
    rp::rgb16ToRgba16f(in.data(), w, h, half.data());

    f = std::fopen(argv[4], "wb");
    if (!f) return 1;
    std::fwrite(out.data(), 1, out.size(), f);
    std::fclose(f);
    f = std::fopen(argv[5], "wb");
    if (!f) return 1;
    std::fwrite(half.data(), 2, half.size(), f);
    std::fclose(f);
    return 0;
}
