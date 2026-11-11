// Herramienta de prueba de la selección de sujeto (sin Android ni modelo).
//   seg_demo input  in.rgb16 w h out.f32          -> escribe la entrada del modelo (3x320x320 floats)
//   seg_demo finish in.rgb16 w h pred.f32 out.pgm -> convierte la salida del modelo en máscara (PGM)
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <vector>

#include "segment.h"

int main(int argc, char** argv) {
    if (argc < 6) return 2;
    int w = std::atoi(argv[3]), h = std::atoi(argv[4]);
    std::vector<uint16_t> in(static_cast<size_t>(w) * h * 3);
    FILE* f = std::fopen(argv[2], "rb");
    if (!f || std::fread(in.data(), 2, in.size(), f) != in.size()) return 1;
    std::fclose(f);
    if (!std::strcmp(argv[1], "input")) {
        std::vector<float> out(3 * rp::kSegSize * rp::kSegSize);
        rp::segmentInput(in.data(), w, h, out.data());
        f = std::fopen(argv[5], "wb");
        std::fwrite(out.data(), 4, out.size(), f);
        std::fclose(f);
        return 0;
    }
    if (argc < 7) return 2;
    std::vector<float> pred(rp::kSegSize * rp::kSegSize);
    f = std::fopen(argv[5], "rb");
    if (!f || std::fread(pred.data(), 4, pred.size(), f) != pred.size()) return 1;
    std::fclose(f);
    rp::SubjectMask m;
    rp::segmentFinish(in.data(), w, h, pred.data(), 1280, m);
    f = std::fopen(argv[6], "wb");
    std::fprintf(f, "P5\n%d %d\n255\n", m.w, m.h);
    std::fwrite(m.a.data(), 1, m.a.size(), f);
    std::fclose(f);
    std::printf("%d %d\n", m.w, m.h);
    return 0;
}
