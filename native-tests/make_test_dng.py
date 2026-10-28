#!/usr/bin/env python3
"""Genera un DNG sintético (CFA RGGB de 16 bits, sin compresión) para probar el pipeline nativo."""
import struct, sys, math

def make_dng(path, w=256, h=192, orientation=1):
    # Escena lineal RGB (0..1): cielo, gradiente, parches de color, sombra profunda y luz quemada.
    def scene(x, y):
        u, v = x / (w - 1), y / (h - 1)
        r = g = b = 0.02 + 0.5 * u                       # gradiente horizontal gris
        if v < 0.30:                                      # cielo
            r, g, b = 0.10 + 0.1 * v, 0.25 + 0.2 * v, 0.55 + 0.2 * (0.3 - v)
        if 0.35 < v < 0.65 and 0.08 < u < 0.22:           # rojo
            r, g, b = 0.45, 0.04, 0.03
        if 0.35 < v < 0.65 and 0.30 < u < 0.44:           # verde
            r, g, b = 0.05, 0.40, 0.06
        if 0.35 < v < 0.65 and 0.52 < u < 0.66:           # gris medio 18 %
            r = g = b = 0.18
        if 0.70 < v < 0.95 and 0.05 < u < 0.30:           # sombra profunda
            r = g = b = 0.004
        if 0.70 < v < 0.95 and 0.70 < u < 0.95:           # altas luces quemadas
            r = g = b = 1.0
        return r, g, b
    pat = [[0, 1], [1, 2]]  # RGGB
    raw = bytearray()
    for y in range(h):
        for x in range(w):
            c = pat[y % 2][x % 2]
            val = int(max(0.0, min(1.0, scene(x, y)[c])) * 60000)
            raw += struct.pack('<H', val)

    def rat(n, d=10000): return struct.pack('<ii', int(round(n * d)), d)
    cm = [3.2404542, -1.5371385, -0.4985314, -0.9692660, 1.8760108, 0.0415560,
          0.0556434, -0.2040259, 1.0572252]
    cm_bytes = b''.join(rat(v) for v in cm)
    neutral = struct.pack('<IIIIII', 1, 1, 1, 1, 1, 1)
    model = b'RawPocket Test\0'

    # (tag, type, count, value bytes)  tipos: 1 BYTE, 2 ASCII, 3 SHORT, 4 LONG, 5 RATIONAL, 10 SRATIONAL
    entries = [
        (254, 4, 1, struct.pack('<I', 0)),
        (256, 4, 1, struct.pack('<I', w)),
        (257, 4, 1, struct.pack('<I', h)),
        (258, 3, 1, struct.pack('<H', 16)),
        (259, 3, 1, struct.pack('<H', 1)),
        (262, 3, 1, struct.pack('<H', 32803)),
        (273, 4, 1, None),  # StripOffsets (se rellena)
        (274, 3, 1, struct.pack('<H', orientation)),
        (277, 3, 1, struct.pack('<H', 1)),
        (278, 4, 1, struct.pack('<I', h)),
        (279, 4, 1, struct.pack('<I', len(raw))),
        (33421, 3, 2, struct.pack('<HH', 2, 2)),
        (33422, 1, 4, bytes([0, 1, 1, 2])),
        (50706, 1, 4, bytes([1, 4, 0, 0])),
        (50707, 1, 4, bytes([1, 1, 0, 0])),
        (50708, 2, len(model), model),
        (50717, 4, 1, struct.pack('<I', 65535)),
        (50721, 10, 9, cm_bytes),
        (50728, 5, 3, neutral),
        (50778, 3, 1, struct.pack('<H', 21)),
    ]
    n = len(entries)
    ifd_off = 8
    data_off = ifd_off + 2 + n * 12 + 4
    blobs, cursor, fixed = [], data_off, []
    for tag, typ, cnt, val in entries:
        if val is not None and len(val) > 4:
            fixed.append((tag, typ, cnt, cursor, val))
            blobs.append(val + (b'\0' if len(val) % 2 else b''))
            cursor += len(blobs[-1])
        else:
            fixed.append((tag, typ, cnt, None, val))
    raw_off = cursor
    out = bytearray(b'II*\0' + struct.pack('<I', ifd_off))
    out += struct.pack('<H', n)
    for tag, typ, cnt, off, val in fixed:
        if tag == 273:
            vb = struct.pack('<I', raw_off)
        elif off is not None:
            vb = struct.pack('<I', off)
        else:
            vb = val.ljust(4, b'\0')
        out += struct.pack('<HHI', tag, typ, cnt) + vb
    out += struct.pack('<I', 0)
    for b in blobs: out += b
    assert len(out) == raw_off
    out += raw
    open(path, 'wb').write(out)

if __name__ == '__main__':
    make_dng(sys.argv[1] if len(sys.argv) > 1 else 'test.dng', orientation=int(sys.argv[2]) if len(sys.argv) > 2 else 1)
