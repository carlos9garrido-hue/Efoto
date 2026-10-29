#!/usr/bin/env python3
"""Genera la imagen de prueba (RGB lineal de 16 bits) para la prueba de paridad CPU/GPU.
Mezcla rampas, colores saturados, extremos (negro, blanco) y valores aleatorios en escala
logarítmica, para cubrir sombras profundas y altas luces."""
import colorsys, random, struct, sys

W, H = 128, 96  # no cuadrada, para probar giros de 90°

def main(path):
    rnd = random.Random(1234)
    px = []
    # Rampa de grises logarítmica (fila superior) y saturados
    for i in range(W):
        v = 10 ** (-4 + 4 * i / (W - 1))
        px.append((v, v, v))
    for i in range(W):
        h = i / W
        px.append([(1, h, 0), (1 - h, 1, 0), (0, 1, h), (0, 1 - h, 1), (h, 0, 1), (1, 0, 1 - h)][i % 6])
    # Barridos de tono completos con distintas saturaciones y valores (para HSL)
    for s_, v_ in [(1.0, 1.0), (0.8, 0.8), (0.5, 0.6), (0.25, 0.4), (0.9, 0.15), (0.3, 0.9)]:
        for i in range(W):
            px.append(colorsys.hsv_to_rgb(i / W, s_, v_))
    # Extremos
    px += [(0, 0, 0), (1, 1, 1), (0.18, 0.18, 0.18), (1, 0, 0), (0, 1, 0), (0, 0, 1)] * 4
    # Aleatorio: cada canal log-uniforme en [1e-4, 1]
    while len(px) < W * H:
        px.append(tuple(10 ** rnd.uniform(-4, 0) for _ in range(3)))
    px = px[: W * H]
    with open(path, 'wb') as f:
        for r, g, b in px:
            f.write(struct.pack('<HHH', *[max(0, min(65535, round(c * 65535))) for c in (r, g, b)]))
    print(f'{path}: {W}x{H}')

if __name__ == '__main__':
    main(sys.argv[1])
