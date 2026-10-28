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
uniform float uParams[39];

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

void main() {
    float exposure = uParams[0], contrast = uParams[1], highlights = uParams[2];
    float shadows = uParams[3], whites = uParams[4], blacks = uParams[5];
    float temperature = uParams[6], tint = uParams[7];
    float vibrance = uParams[8], saturation = uParams[9];

    vec3 c = texture(uTex, vUv).rgb;

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

    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
