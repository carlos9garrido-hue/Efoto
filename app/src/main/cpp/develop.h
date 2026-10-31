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
    static constexpr int kGeomStart = 10 + kCurvePoints + kHslBands * 3;  // 39
    static constexpr int kFxStart = kGeomStart + 6;                        // 45
    static constexpr int kMaskStart = kFxStart + 16;                       // 61
    static constexpr int kMaskCount = 4;       // máscaras locales (degradados)
    static constexpr int kMaskFloats = 13;     // valores por máscara, ver Mask más abajo
    static constexpr int kCount = kMaskStart + kMaskCount * kMaskFloats;   // 113
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
    // Geometría. Orden: primero se gira (rotate90), luego se endereza (angle) sobre ese marco
    // y por último se recorta (insets, como fracción del marco girado).
    float angle = 0;      // grados, -45 .. +45; positivo = la foto gira en sentido horario
    float rotate90 = 0;   // giros de 90° en sentido horario: 0, 1, 2 ó 3
    float cropL = 0, cropT = 0, cropR = 0, cropB = 0;  // recorte por lado, 0 .. 1

    // Detalle (necesita vecinos: ver Aux). Claridad, textura y neblina -1..1; nitidez y ruido 0..1.
    float clarity = 0, texture = 0, dehaze = 0;
    float sharpen = 0, nrLuma = 0, nrColor = 0;
    // Efectos por píxel. Viñeta -1 (oscurece) .. +1 (aclara); punto medio -1 .. +1; grano 0 .. 1.
    float vigAmount = 0, vigMid = 0, grain = 0;
    // Gradación de color: tono (grados 0..360) y saturación (0..1) de sombras, medios y luces,
    // y el balance entre ellos (-1 .. +1).
    float gradeHue[3] = {0, 0, 0};
    float gradeSat[3] = {0, 0, 0};
    float gradeBalance = 0;

    // Ediciones locales. Cada máscara (13 valores):
    //   0 tipo (0 apagada, 1 lineal, 2 radial)
    //   1-4 lineal: (x1,y1) donde empieza el efecto pleno y (x2,y2) donde llega a cero
    //       radial: centro (x1,y1) y radios (x2,y2); todo en 0..1 sobre la imagen final
    //   5 suavizado del borde (radial, 0..1)   6 invertir (>0.5)
    //   7-12 ajustes locales: exposición (EV), contraste, luces, sombras, temperatura, saturación
    float masks[kMaskCount * kMaskFloats] = {};

    bool hasLocal() const {
        for (int m = 0; m < kMaskCount; ++m) {
            const float* k = masks + m * kMaskFloats;
            if (k[0] < 0.5f) continue;
            for (int i = 7; i < 13; ++i)
                if (k[i] != 0.f) return true;
        }
        return false;
    }

    bool hasSpatial() const {
        return clarity != 0.f || texture != 0.f || dehaze != 0.f || sharpen > 0.f || nrLuma > 0.f ||
               nrColor > 0.f;
    }

    bool hasGeometry() const {
        return angle != 0.f || rotate90 != 0.f || cropL > 0.f || cropT > 0.f || cropR > 0.f ||
               cropB > 0.f;
    }

    static Params fromArray(const float* a);
};

// Capas auxiliares para los efectos que miran a los vecinos (claridad, textura, neblina, nitidez y
// reducción de ruido). Se calculan una vez por imagen, no dependen de los ajustes, y se guardan en
// half-float: son exactamente los bytes que la app sube a la GPU, así CPU y GPU leen lo mismo.
//   a (RGBA): rgb = la imagen con un desenfoque pequeño (lineal), a = luminancia con desenfoque grande
//   b (RG)  : r = luminancia con desenfoque medio, g = mínimo de canales con desenfoque grande
// Los radios son fracciones del lado largo, así que se ven igual en la vista previa y al exportar.
struct Aux {
    int w = 0, h = 0;
    std::vector<uint16_t> a;  // w*h*4
    std::vector<uint16_t> b;  // w*h*2
    bool empty() const { return w <= 0 || h <= 0; }
};

// step = 1 usa la imagen tal cual; 2 trabaja a media resolución (ahorra memoria al exportar).
void buildAux(const uint16_t* src, int w, int h, int step, Aux& out);

// Tamaño de la imagen final tras girar y recortar una fuente de srcW x srcH.
void outputSize(int srcW, int srcH, const Params& p, int& outW, int& outH);

// Revela aplicando la geometría (giro, enderezado y recorte) con muestreo bilineal.
// dst: outW * outH * 3 bytes (use outputSize). Los píxeles fuera de la foto se repiten del borde.
void developGeometry(const uint16_t* src, int w, int h, const Params& p,
                     uint8_t* dst, int outW, int outH, int threads, const Aux* aux = nullptr);

class Developer {
public:
    explicit Developer(const Params& p);
    // Tamaño de la imagen final: el grano y la viñeta dependen de él.
    void setOutput(int outW, int outH);
    // r,g,b: RGB lineal (1.0 = blanco). aux: 6 valores (a.rgba, b.rg) de esa posición o nullptr.
    // (u, v): posición en la imagen final (0..1). out: 3 bytes sRGB.
    void pixel(float r, float g, float b, const float* aux, float u, float v, uint8_t* out) const;
    void pixel(float r, float g, float b, uint8_t* out) const { pixel(r, g, b, nullptr, 0.5f, 0.5f, out); }

private:
    void applyHsl(float c[3]) const;
    float curveAt(float x) const;

    Params p_;
    bool spatial_ = false, grade_ = false;
    int nMask_ = 0;
    float mask_[Params::kMaskCount][Params::kMaskFloats];
    int outW_ = 1, outH_ = 1, gridW_ = 1, gridH_ = 1;
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
                   uint8_t* dst, int dstStride, int threads, const Aux* aux = nullptr);

// Revela RGB16 -> RGB8 empaquetado (w*h*3 bytes).
void developToRgb8(const uint16_t* src, int w, int h, const Params& p,
                   uint8_t* dst, int threads, const Aux* aux = nullptr);

}  // namespace rp
