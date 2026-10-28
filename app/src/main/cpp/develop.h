// Motor de revelado de RawPocket (C++ puro, sin dependencias de Android).
// Entrada: RGB lineal de 16 bits (sRGB primarios, gamma 1.0) producido por LibRaw.
// Salida: sRGB de 8 bits.
#pragma once
#include <cstdint>
#include <vector>

namespace rp {

// El orden de los campos debe coincidir con EditParams.toFloatArray() en Kotlin.
struct Params {
    // 10 básicos + 5 puntos de curva + 8 bandas de color x (tono, saturación, luminosidad)
    static constexpr int kCurvePoints = 5;  // en x = 0, .25, .5, .75, 1
    static constexpr int kHslBands = 8;     // rojo, naranja, amarillo, verde, aqua, azul, púrpura, magenta
    static constexpr int kCount = 10 + kCurvePoints + kHslBands * 3;
    float exposure = 0;     // EV, -5 .. +5
    float contrast = 0;     // -1 .. +1
    float highlights = 0;   // -1 .. +1
    float shadows = 0;      // -1 .. +1
    float whites = 0;       // -1 .. +1
    float blacks = 0;       // -1 .. +1
    float temperature = 0;  // -1 (frío) .. +1 (cálido), relativo al balance de la cámara
    float tint = 0;         // -1 (verde) .. +1 (magenta)
    float vibrance = 0;     // -1 .. +1
    float saturation = 0;   // -1 .. +1
    // Curva tonal: desplazamiento respecto a la diagonal en cada punto (-0.25 .. +0.25).
    float curve[kCurvePoints] = {0, 0, 0, 0, 0};
    // HSL: por banda [tono, saturación, luminosidad], cada uno -1 .. +1.
    float hsl[kHslBands * 3] = {};

    static Params fromArray(const float* a);
};

class Developer {
public:
    explicit Developer(const Params& p);
    // r,g,b: RGB lineal (1.0 = blanco). out: 3 bytes sRGB.
    void pixel(float r, float g, float b, uint8_t* out) const;

private:
    void applyHsl(float c[3]) const;
    float curveAt(float x) const;

    Params p_;
    float gain_[3];
    float hlk_;
    bool curveActive_ = false;
    bool hslActive_ = false;
    float curveY_[Params::kCurvePoints];
    float curveM_[Params::kCurvePoints];  // tangentes (monótonas, estilo PCHIP)
};

// Reduce una imagen RGB16 tomando el promedio de bloques step x step.
// Devuelve las nuevas dimensiones en outW/outH.
void boxDownsample16(const uint16_t* src, int w, int h, int step,
                     std::vector<uint16_t>& out, int& outW, int& outH);

// Convierte RGB16 (0..65535) a RGBA de half-float (0..1, alfa = 1) para subirlo como textura
// GL_RGBA16F. dst debe tener w*h*4 elementos de 16 bits.
uint16_t floatToHalf(float f);
float halfToFloat(uint16_t h);
void rgb16ToRgba16f(const uint16_t* src, int w, int h, uint16_t* dst);

// Revela RGB16 -> RGBA8 (alfa = 255). dstStride en bytes.
void developToRgba(const uint16_t* src, int w, int h, const Params& p,
                   uint8_t* dst, int dstStride, int threads);

// Revela RGB16 -> RGB8 empaquetado (w*h*3 bytes).
void developToRgb8(const uint16_t* src, int w, int h, const Params& p,
                   uint8_t* dst, int threads);

}  // namespace rp
