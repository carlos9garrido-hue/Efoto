#version 300 es
// Revelado en GPU. Debe mantenerse idéntico, paso a paso, a Developer::pixel() en
// app/src/main/cpp/develop.cpp (la exportación a resolución completa usa ese código en CPU).
// La prueba native-tests/gpu/parity.js comprueba que ambos coinciden.
precision highp float;
precision highp sampler2D;

uniform sampler2D uTex;      // RGB lineal (sRGB primarios), 1.0 = blanco
// Orden (igual que Params en develop.h):
//   0-9   exposición, contraste, luces altas, sombras, blancos, negros,
//         temperatura, matiz, intensidad, saturación
//   10-14 curva tonal: desplazamiento en x = 0, .25, .5, .75, 1
//   15-38 HSL: 8 bandas (rojo, naranja, amarillo, verde, aqua, azul, púrpura, magenta) x (tono, sat., lum.)
//   39-44 geometría: enderezado (grados), giros de 90°, recorte izquierda/arriba/derecha/abajo (0..1)
//   45-50 detalle: claridad, textura, neblina, nitidez, ruido (luminancia), ruido (color)
//   51-53 efectos: viñeta (cantidad, punto medio), grano
//   54-60 gradación: tono y saturación de sombras, medios y luces; balance
uniform float uParams[61];
// Capas auxiliares para los efectos con vecinos (ver Aux en develop.h):
//   uAuxA: rgb = imagen con desenfoque pequeño, a = luminancia con desenfoque grande
//   uAuxB: r = luminancia con desenfoque medio, g = mínimo de canales con desenfoque grande
uniform sampler2D uAuxA;
uniform sampler2D uAuxB;
// (ancho, alto) de la imagen final y de la cuadrícula del grano.
uniform vec4 uOut;
// (cos, sin) del ángulo de enderezado, calculados fuera del shader: sin() y cos() en GPU solo
// garantizan un error de 2^-11, que desplazaría el muestreo varias décimas de píxel.
uniform vec2 uRot;

in vec2 vUv;
out vec4 outColor;

const float kBandCenter[9] = float[9](0.0, 30.0, 60.0, 120.0, 180.0, 240.0, 270.0, 300.0, 360.0);

float mod6(float x) { return x - 6.0 * floor(x / 6.0); }

// Curva: Hermite cúbico con tangentes monótonas (idéntico a Developer::curveAt en C++).
float curveAt(float x) {
    float y[5], d[4], m[5];
    for (int i = 0; i < 5; i++) y[i] = clamp(0.25 * float(i) + uParams[10 + i], 0.0, 1.0);
    for (int i = 0; i < 4; i++) d[i] = (y[i + 1] - y[i]) / 0.25;
    m[0] = d[0];
    m[4] = d[3];
    for (int i = 1; i < 4; i++)
        m[i] = (d[i - 1] * d[i] <= 0.0) ? 0.0 : 2.0 * d[i - 1] * d[i] / (d[i - 1] + d[i]);
    float xs = x * 4.0;
    int i = min(3, int(floor(xs)));
    float t = xs - float(i), t2 = t * t, t3 = t2 * t;
    float r = (2.0 * t3 - 3.0 * t2 + 1.0) * y[i] + (t3 - 2.0 * t2 + t) * 0.25 * m[i] +
              (-2.0 * t3 + 3.0 * t2) * y[i + 1] + (t3 - t2) * 0.25 * m[i + 1];
    return clamp(r, 0.0, 1.0);
}

// HSL por color (idéntico a Developer::applyHsl en C++). Los grises no cambian.
vec3 applyHsl(vec3 c) {
    float mx = max(c.r, max(c.g, c.b));
    float mn = min(c.r, min(c.g, c.b));
    float d = mx - mn;
    if (d <= 0.0 || mx <= 0.0) return c;

    float s0 = d / mx, v = mx, h;
    if (mx == c.r) h = (c.g - c.b) / d;
    else if (mx == c.g) h = (c.b - c.r) / d + 2.0;
    else h = (c.r - c.g) / d + 4.0;
    h = mod6(h) * 60.0;

    int k = 0;
    for (int i = 1; i < 8; i++)
        if (h >= kBandCenter[i]) k = i;
    float t = (h - kBandCenter[k]) / (kBandCenter[k + 1] - kBandCenter[k]);
    int a = k, b = (k + 1) % 8;
    float dh = (1.0 - t) * uParams[15 + a * 3] + t * uParams[15 + b * 3];
    float ds = (1.0 - t) * uParams[16 + a * 3] + t * uParams[16 + b * 3];
    float dl = (1.0 - t) * uParams[17 + a * 3] + t * uParams[17 + b * 3];

    h += dh * 30.0;
    float s = clamp(s0 * (1.0 + ds), 0.0, 1.0);
    v = clamp(v * (1.0 + 0.5 * dl * s0), 0.0, 1.0);

    float h6 = mod6(h / 60.0);
    vec3 K = vec3(0.0, 4.0, 2.0);
    vec3 q = clamp(abs(vec3(mod6(h6 + K.x), mod6(h6 + K.y), mod6(h6 + K.z)) - 3.0) - 1.0, 0.0, 1.0);
    return v * (1.0 - s + s * q);
}

// Geometría: posición de la imagen final (0..1, y hacia abajo) -> posición en la foto original (0..1).
// Idéntica a Geo::sourceUv en develop.cpp: primero se gira (90°), luego se endereza sobre ese
// marco y por último se recorta.
vec2 sourceUv(vec2 uv) {
    float ca = uRot.x;
    float sa = uRot.y;
    int k = int(floor(uParams[40] + 0.5)) & 3;
    float l = clamp(uParams[41], 0.0, 1.0), t = clamp(uParams[42], 0.0, 1.0);
    float r = clamp(uParams[43], 0.0, 1.0), b = clamp(uParams[44], 0.0, 1.0);
    vec2 src = vec2(textureSize(uTex, 0));
    float wA = ((k & 1) == 1) ? src.y : src.x;
    float hA = ((k & 1) == 1) ? src.x : src.y;

    float ax = l + (1.0 - r - l) * uv.x;
    float ay = t + (1.0 - b - t) * uv.y;
    float cx = 0.5 * wA, cy = 0.5 * hA;
    float dx = ax * wA - cx, dy = ay * hA - cy;
    float sx = (cx + ca * dx + sa * dy) / wA;
    float sy = (cy - sa * dx + ca * dy) / hA;
    if (k == 1) return vec2(sy, 1.0 - sx);
    if (k == 2) return vec2(1.0 - sx, 1.0 - sy);
    if (k == 3) return vec2(1.0 - sy, sx);
    return vec2(sx, sy);
}

// Interpolación bilineal hecha a mano (4 texelFetch, bordes repetidos). El filtro de textura del
// hardware usa pesos de precisión limitada que varían entre GPUs; así la vista previa es
// idéntica en cualquier dispositivo y coincide con developGeometry() de C++ (la exportación).
vec3 sampleBilinear(vec2 uv) {
    ivec2 sz = textureSize(uTex, 0);
    vec2 f = uv * vec2(sz) - 0.5;
    vec2 fl = floor(f);
    vec2 t = f - fl;
    // Dentro de 1e-4 px del centro de un texel se ajusta a él: el redondeo de coma flotante no
    // debe mezclar un vecino brillante en un negro puro (el motor multiplica los negros por >100).
    t.x = t.x < 1e-4 ? 0.0 : (t.x > 0.9999 ? 1.0 : t.x);
    t.y = t.y < 1e-4 ? 0.0 : (t.y > 0.9999 ? 1.0 : t.y);
    ivec2 p0 = clamp(ivec2(fl), ivec2(0), sz - 1);
    ivec2 p1 = clamp(ivec2(fl) + 1, ivec2(0), sz - 1);
    vec3 a = texelFetch(uTex, ivec2(p0.x, p0.y), 0).rgb;
    vec3 b = texelFetch(uTex, ivec2(p1.x, p0.y), 0).rgb;
    vec3 c = texelFetch(uTex, ivec2(p0.x, p1.y), 0).rgb;
    vec3 e = texelFetch(uTex, ivec2(p1.x, p1.y), 0).rgb;
    vec3 top = a + (b - a) * t.x;
    vec3 bottom = c + (e - c) * t.x;
    return top + (bottom - top) * t.y;
}

// Igual que auxSample() en develop.cpp: misma bilineal manual que la fuente.
void sampleAux(vec2 uv, out vec4 a, out vec2 b) {
    ivec2 sz = textureSize(uAuxA, 0);
    vec2 f = uv * vec2(sz) - 0.5;
    vec2 fl = floor(f);
    vec2 t = f - fl;
    t.x = t.x < 1e-4 ? 0.0 : (t.x > 0.9999 ? 1.0 : t.x);
    t.y = t.y < 1e-4 ? 0.0 : (t.y > 0.9999 ? 1.0 : t.y);
    ivec2 p0 = clamp(ivec2(fl), ivec2(0), sz - 1);
    ivec2 p1 = clamp(ivec2(fl) + 1, ivec2(0), sz - 1);
    vec4 a00 = texelFetch(uAuxA, ivec2(p0.x, p0.y), 0);
    vec4 a10 = texelFetch(uAuxA, ivec2(p1.x, p0.y), 0);
    vec4 a01 = texelFetch(uAuxA, ivec2(p0.x, p1.y), 0);
    vec4 a11 = texelFetch(uAuxA, ivec2(p1.x, p1.y), 0);
    vec4 at = a00 + (a10 - a00) * t.x;
    vec4 ab = a01 + (a11 - a01) * t.x;
    a = at + (ab - at) * t.y;
    vec2 b00 = texelFetch(uAuxB, ivec2(p0.x, p0.y), 0).rg;
    vec2 b10 = texelFetch(uAuxB, ivec2(p1.x, p0.y), 0).rg;
    vec2 b01 = texelFetch(uAuxB, ivec2(p0.x, p1.y), 0).rg;
    vec2 b11 = texelFetch(uAuxB, ivec2(p1.x, p1.y), 0).rg;
    vec2 bt = b00 + (b10 - b00) * t.x;
    vec2 bb = b01 + (b11 - b01) * t.x;
    b = bt + (bb - bt) * t.y;
}

float lum(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

// Color puro de un tono en grados (igual que hueRgb en develop.cpp).
vec3 hueRgb(float deg) {
    float h6 = mod6(deg / 60.0);
    vec3 K = vec3(0.0, 4.0, 2.0);
    return clamp(abs(vec3(mod6(h6 + K.x), mod6(h6 + K.y), mod6(h6 + K.z)) - 3.0) - 1.0, 0.0, 1.0);
}

// Ruido de cuadrícula en [-1, 1] (igual que gridNoise en develop.cpp).
float gridNoise(int x, int y) {
    uint h = uint(x) * 374761393u + uint(y) * 668265263u;
    h = (h ^ (h >> 13)) * 1274126177u;
    h = h ^ (h >> 16);
    return float(h & 0xFFFFu) * (1.0 / 32767.5) - 1.0;
}

void main() {
    float exposure = uParams[0], contrast = uParams[1], highlights = uParams[2];
    float shadows = uParams[3], whites = uParams[4], blacks = uParams[5];
    float temperature = uParams[6], tint = uParams[7];
    float vibrance = uParams[8], saturation = uParams[9];

    vec2 srcUv = sourceUv(vUv);
    vec3 c = sampleBilinear(srcUv);

    // Efectos con vecinos (sobre la imagen lineal, antes de exposición y tonos).
    float clarity = uParams[45], texAmt = uParams[46], dehaze = uParams[47];
    float sharpen = uParams[48], nrLuma = uParams[49], nrColor = uParams[50];
    bool spatial = clarity != 0.0 || texAmt != 0.0 || dehaze != 0.0 || sharpen > 0.0 ||
                   nrLuma > 0.0 || nrColor > 0.0;
    if (spatial) {
        vec4 ax;
        vec2 bx;
        sampleAux(srcUv, ax, bx);
        vec3 N = ax.rgb;
        float Lc = ax.a, Lm = bx.x, Hz = bx.y;
        if (dehaze != 0.0) {
            const float A = 0.95;  // luz ambiente supuesta
            if (dehaze > 0.0) {
                float tr = min(1.0, max(0.25, 1.0 - 0.9 * dehaze * (Hz / A)));
                c = (c - A) / tr + A;
            } else {
                float k = -dehaze * 0.5;
                c = c + (A - c) * k;
            }
            c = max(c, 0.0);
        }
        float Yn = lum(N);
        if (nrColor > 0.0) {
            float Y = lum(c);
            c += nrColor * ((N - Yn) - (c - Y));
        }
        if (nrLuma > 0.0) {
            float Y = lum(c);
            float d = abs(sqrt(max(Y, 0.0)) - sqrt(max(Yn, 0.0)));
            float w = nrLuma * (1.0 - smoothstep(0.02, 0.12, d));
            c += w * (Yn - Y);
        }
        if (sharpen > 0.0 || texAmt != 0.0 || clarity != 0.0) {
            float Y = max(lum(c), 0.0);
            float s = sqrt(Y), sS = sqrt(max(Yn, 0.0));
            float sT = sqrt(max(Lm, 0.0)), sC = sqrt(max(Lc, 0.0));
            float wC = max(1.0 - (2.0 * sC - 1.0) * (2.0 * sC - 1.0), 0.0);
            float s2 = s + sharpen * 0.9 * (s - sS) + texAmt * 0.9 * (s - sT) +
                       clarity * 1.2 * wC * (s - sC);
            s2 = max(s2, 0.0);
            float sc = min(((s2 * s2) + 0.002) / (Y + 0.002), 8.0);
            c *= sc;
        }
    }

    // Exposición y balance de blancos relativo al de la cámara.
    float ev = exp2(exposure);
    c *= vec3(ev * (1.0 + 0.35 * temperature),
              ev * (1.0 - 0.25 * tint),
              ev * (1.0 - 0.35 * temperature));

    // Recuperación de altas luces en lineal: compresión suave sobre la rodilla.
    const float knee = 0.6;
    float hlk = max(0.0, -highlights) * 3.0;
    vec3 t = max(c - knee, 0.0);
    c = min(c, vec3(knee)) + t / (1.0 + hlk * t);

    // Codificación sRGB.
    c = clamp(c, 0.0, 1.0);
    vec3 lo = 12.92 * c;
    vec3 hi = 1.055 * pow(c, vec3(1.0 / 2.4)) - 0.055;
    c = mix(hi, lo, lessThanEqual(c, vec3(0.0031308)));

    // Tonos por luminancia en espacio codificado.
    float y = dot(c, vec3(0.2126, 0.7152, 0.0722));
    float y2 = y;
    y2 += shadows * 0.30 * (1.0 - smoothstep(0.10, 0.65, y)) * smoothstep(0.0, 0.08, y);
    y2 += highlights * 0.25 * smoothstep(0.35, 0.90, y);
    y2 += whites * 0.20 * smoothstep(0.60, 1.00, y);
    y2 += blacks * 0.20 * (1.0 - smoothstep(0.0, 0.35, y));
    y2 = 0.5 + (y2 - 0.5) * (1.0 + 0.8 * contrast);
    y2 = clamp(y2, 0.0, 1.0);

    float curveSum = 0.0;
    for (int i = 0; i < 5; i++) curveSum += abs(uParams[10 + i]);
    if (curveSum > 0.0) y2 = curveAt(y2);

    const float eps = 0.002;
    c *= (y2 + eps) / (y + eps);

    // Intensidad (protege colores ya saturados) y saturación global.
    float mx = max(c.r, max(c.g, c.b));
    float mn = min(c.r, min(c.g, c.b));
    float chroma = clamp(mx - mn, 0.0, 1.0);
    float sat = max((1.0 + saturation) * (1.0 + vibrance * (1.0 - chroma)), 0.0);
    c = clamp(y2 + (c - y2) * sat, 0.0, 1.0);

    float hslSum = 0.0;
    for (int i = 15; i < 39; i++) hslSum += abs(uParams[i]);
    if (hslSum > 0.0) c = applyHsl(c);

    // Gradación de color: un tinte distinto en sombras, medios y luces.
    if (uParams[55] > 0.0 || uParams[57] > 0.0 || uParams[59] > 0.0) {
        float yg = lum(c);
        float bal = uParams[60] * 0.25;
        float wS = 1.0 - smoothstep(0.10, 0.55 + bal, yg);
        float wH = smoothstep(0.45 + bal, 0.90, yg);
        float wM = clamp(1.0 - wS - wH, 0.0, 1.0);
        float zone[3] = float[3](wS, wM, wH);
        for (int z = 0; z < 3; z++) {
            float sat = uParams[55 + z * 2];
            if (sat <= 0.0) continue;
            vec3 tint = hueRgb(uParams[54 + z * 2]);
            float tl = lum(tint);
            c += (tint - tl) * (sat * zone[z] * 0.30);
        }
        c = clamp(c, 0.0, 1.0);
    }

    // Viñeta: oscurece (o aclara) hacia las esquinas de la imagen final.
    float vigAmount = uParams[51];
    if (vigAmount != 0.0) {
        float aspect = uOut.x / uOut.y;
        float dx = (vUv.x - 0.5) * aspect, dy = vUv.y - 0.5;
        float d = sqrt(dx * dx + dy * dy) / (0.5 * sqrt(aspect * aspect + 1.0));
        float tv = smoothstep(0.45 + 0.4 * uParams[52], 1.0, d);
        if (vigAmount < 0.0) c *= 1.0 + vigAmount * tv;
        else c += (1.0 - c) * vigAmount * tv;
    }

    // Grano: más visible en medios tonos que en negros y blancos.
    float grain = uParams[53];
    if (grain > 0.0) {
        int gx = int(floor(vUv.x * uOut.z));
        int gy = int(floor(vUv.y * uOut.w));
        float yq = lum(c);
        float amp = grain * 0.10 * (0.4 + 1.6 * yq * (1.0 - yq));
        c += gridNoise(gx, gy) * amp;
    }

    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
