#version 300 es
// Revelado en GPU. Debe mantenerse idéntico, paso a paso, a Developer::pixel() en
// app/src/main/cpp/develop.cpp (la exportación a resolución completa usa ese código en CPU).
// La prueba native-tests/gpu/parity.js comprueba que ambos coinciden.
precision highp float;
precision highp sampler2D;

uniform sampler2D uTex;      // RGB lineal (sRGB primarios), 1.0 = blanco
// Orden: exposición, contraste, luces altas, sombras, blancos, negros,
//        temperatura, matiz, intensidad, saturación  (igual que Params en develop.h)
uniform float uParams[10];

in vec2 vUv;
out vec4 outColor;

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

    const float eps = 0.002;
    c *= (y2 + eps) / (y + eps);

    // Intensidad (protege colores ya saturados) y saturación global.
    float mx = max(c.r, max(c.g, c.b));
    float mn = min(c.r, min(c.g, c.b));
    float chroma = clamp(mx - mn, 0.0, 1.0);
    float sat = max((1.0 + saturation) * (1.0 + vibrance * (1.0 - chroma)), 0.0);
    c = y2 + (c - y2) * sat;

    outColor = vec4(clamp(c, 0.0, 1.0), 1.0);
}
