# EFOTO (RawPocket)

Revelador de fotos RAW para Android, pensado como un "Lightroom de bolsillo": ediciones no destructivas, lectura de RAW de la mayoría de cámaras (LibRaw) y exportación a JPEG a resolución completa.

## Cómo compilar

1. Instala **Android Studio** (Ladybug o más reciente) con SDK 35 y **NDK 27.0.12077973** + **CMake 3.22.1** (SDK Manager → SDK Tools).
2. `File → Open` y elige esta carpeta. Android Studio genera el Gradle wrapper al sincronizar.
3. Ejecuta en un teléfono Android 10+ (minSdk 29) o en un emulador x86_64.

## Estado (v0.1 · MVP)

Hecho:
- Lectura de RAW con LibRaw (CR2/CR3, NEF, ARW, DNG, RAF, ORF, RW2…), orientación y metadatos EXIF.
- Revelado: exposición, contraste, luces altas, sombras, blancos, negros, temperatura, matiz, intensidad, saturación.
- **Revelado en GPU** (OpenGL ES 3.0): la vista previa es una textura half-float y los sliders solo envían 10 números al shader (`app/src/main/assets/shaders/develop.frag`). Sin copias de píxeles CPU↔GPU al editar.
- La exportación usa el mismo algoritmo en C++ (CPU) a resolución completa; una prueba automática garantiza que ambos coinciden (diferencia máxima de 1 nivel de 8 bits).
- Ediciones guardadas como JSON por foto (el RAW nunca se modifica).
- Exportación JPEG a resolución completa a `Pictures/RawPocket`.
- Mantener pulsada la foto muestra el original.
- Abrir RAW desde el explorador de archivos / galería.

Pendiente (siguientes fases):
- Exportación acelerada por GPU (hoy se exporta en CPU multihilo).
- Curvas, HSL por color, recorte/rotación, nitidez, reducción de ruido, viñeta, grano.
- Catálogo (biblioteca con valoraciones y etiquetas), presets, copiar/pegar ajustes.
- Máscaras locales, eliminación de manchas, perfiles de lente, exportación DNG/TIFF.
- DNG con compresión JPEG con pérdida (requiere compilar LibRaw con libjpeg).

## Pruebas en PC (sin Android)

```
# Núcleo nativo: decodificación RAW, revelado, conversión half-float
cmake -S native-tests -B build-tests && cmake --build build-tests -j
ctest --test-dir build-tests --output-on-failure

# Paridad GPU/CPU: ejecuta el shader real en WebGL2 (Chromium headless) y lo compara con el motor C++
cd native-tests/gpu && node parity.js ../../build-tests/dump_cpu /tmp/parity   # requiere playwright
```

**Regla de oro:** si cambias un ajuste, cámbialo en `develop.frag` **y** en `develop.cpp` (`Developer::pixel`), y ejecuta `parity.js`.

## Licencias

LibRaw se incluye bajo LGPL 2.1 / CDDL 1.0 (ver `app/src/main/cpp/third_party/LibRaw`). `stb_image_write` es de dominio público.
