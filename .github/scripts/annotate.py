#!/usr/bin/env python3
"""Convierte los errores del registro de Gradle en anotaciones de GitHub Actions (::error).

Reconoce errores de Kotlin ("e: file:///ruta/Archivo.kt:12:5 mensaje"), de clang/C++
("/ruta/archivo.cpp:12:5: error: mensaje") y el bloque "What went wrong" de Gradle.
GitHub muestra como máximo 10 anotaciones de error por paso, así que se priorizan.
"""
import os
import re
import sys

MAX = 10


def rel(path: str) -> str:
    ws = os.environ.get("GITHUB_WORKSPACE", "")
    return path[len(ws) + 1:] if ws and path.startswith(ws + "/") else path


def esc(s: str) -> str:
    return s.replace("%", "%25").replace("\r", "%0D").replace("\n", "%0A")[:900]


def main(log_path: str) -> None:
    lines = open(log_path, errors="replace").read().splitlines()
    found = []  # (archivo, línea, mensaje)
    for i, line in enumerate(lines):
        m = re.match(r"^e: file://(.+?):(\d+):(\d+)\s+(.*)$", line)
        if m:
            found.append((rel(m[1]), m[2], "Kotlin: " + m[4]))
            continue
        m = re.match(r"^(/.+?):(\d+):(\d+): (?:fatal )?error: (.*)$", line)
        if m:
            found.append((rel(m[1]), m[2], "C++: " + m[4]))
            continue
        if line.startswith("* What went wrong:"):
            block = " | ".join(l.strip() for l in lines[i + 1:i + 6] if l.strip() and not l.startswith("* Try"))
            found.insert(0, ("", "", "Gradle: " + block))
    seen = set()
    for f, n, msg in found:
        key = (f, n, msg)
        if key in seen:
            continue
        seen.add(key)
        if len(seen) > MAX:
            break
        loc = f"file={f},line={n}" if f else ""
        print(f"::error {loc}::{esc(msg)}")


if __name__ == "__main__":
    main(sys.argv[1])
