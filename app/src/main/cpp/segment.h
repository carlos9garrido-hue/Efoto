// Selección de sujeto: preparación de la entrada del modelo (U²-Net pequeño, 320x320) y
// conversión de su salida en una máscara fina sobre la foto, ajustada a los bordes con un
// filtro guiado. El modelo en sí corre en Kotlin (ONNX Runtime); aquí está todo lo demás.
#pragma once
#include <cstdint>
#include <string>

#include "develop.h"

namespace rp {

constexpr int kSegSize = 320;

// RGB16 lineal (w x h) -> tensor CHW de 3 x 320 x 320, normalizado como espera el modelo.
// out: 3 * kSegSize * kSegSize floats.
void segmentInput(const uint16_t* rgb16, int w, int h, float* out);

// Salida del modelo (kSegSize x kSegSize, cualquier rango) -> máscara de la foto. La máscara
// mide como mucho maxEdge px de lado largo y sus bordes siguen los de la imagen.
void segmentFinish(const uint16_t* rgb16, int w, int h, const float* pred, int maxEdge,
                   SubjectMask& out);

// Archivo de la máscara de una foto: ancho y alto (int32) y luego los bytes. 0 = bien.
int saveSubjectMask(const SubjectMask& m, const std::string& path);
int loadSubjectMask(SubjectMask& m, const std::string& path);

}  // namespace rp
