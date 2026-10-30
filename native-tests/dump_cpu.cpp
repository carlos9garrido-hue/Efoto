// Herramienta de la prueba de paridad CPU/GPU.
// Uso: dump_cpu <in.rgb16> <w> <h> <out.rgb8> <out.half> <out.auxa> <out.auxb> p0 p1 ... p60
//   (imprime "anchoFinal altoFinal")
//   out.auxa / out.auxb : capas auxiliares (RGBA16F y RG16F) de los efectos con vecinos, tal como se suben a la GPU
//   in.rgb16 : RGB lineal de 16 bits little-endian (w*h*3 valores)
//   out.rgb8 : resultado del motor CPU (lo que exporta la app)
//   out.half : la misma entrada convertida a RGBA half-float (lo que la app sube a la GPU)
#include <cstdio>
#include <cstdlib>
#include <vector>

#include "develop.h"

int main(int argc, char** argv) {
    if (argc != 8 + rp::Params::kCount) {
        std::fprintf(stderr, "uso: %s in.rgb16 w h out.rgb8 out.half out.auxa out.auxb p0..p60\n", argv[0]);
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
    for (int i = 0; i < rp::Params::kCount; ++i) a[i] = static_cast<float>(std::atof(argv[8 + i]));

    const rp::Params params = rp::Params::fromArray(a);
    int ow = w, oh = h;
    if (params.hasGeometry()) rp::outputSize(w, h, params, ow, oh);
    rp::Aux aux;
    rp::buildAux(in.data(), w, h, 1, aux);
    std::vector<uint8_t> out(static_cast<size_t>(ow) * oh * 3);
    if (params.hasGeometry())
        rp::developGeometry(in.data(), w, h, params, out.data(), ow, oh, 1, &aux);
    else
        rp::developToRgb8(in.data(), w, h, params, out.data(), 1, &aux);
    std::printf("%d %d\n", ow, oh);  // tamaño de la imagen final
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
    f = std::fopen(argv[6], "wb");
    if (!f) return 1;
    std::fwrite(aux.a.data(), 2, aux.a.size(), f);
    std::fclose(f);
    f = std::fopen(argv[7], "wb");
    if (!f) return 1;
    std::fwrite(aux.b.data(), 2, aux.b.size(), f);
    std::fclose(f);
    return 0;
}
