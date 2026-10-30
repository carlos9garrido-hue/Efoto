// Herramientas de análisis sobre una vista previa pequeña: histograma, ajuste automático y
// balance de blancos por muestreo. Sin dependencias de Android.
#pragma once
#include <cstdint>

#include "develop.h"

namespace rp {

// Histograma 4 x 256 de la imagen ya revelada (con geometría): [0..255]=R, [256..511]=G,
// [512..767]=B, [768..1023]=luminancia.
void histogram8(const uint16_t* src, int w, int h, const Params& p, int32_t out[1024]);

// Propone ajustes básicos para una foto: out = {exposición (EV), contraste, luces, sombras,
// blancos, negros}, en las mismas unidades que Params.
void autoAdjust(const uint16_t* src, int w, int h, float out[6]);

// Temperatura y matiz que vuelven neutro un parche de color lineal (r,g,b > 0). Es la inversa
// exacta de las ganancias de Developer. Devuelve false si el parche es negro.
bool whiteBalanceFromRgb(float r, float g, float b, float& temperature, float& tint);

// Color lineal medio de un parche de 5x5 píxeles de la imagen girada/recortada según la
// geometría de `p` (u, v en 0..1 sobre esa imagen). Ignora el resto de ajustes.
bool samplePatchLinear(const uint16_t* src, int w, int h, const Params& p, float u, float v,
                       float rgb[3]);

}  // namespace rp
