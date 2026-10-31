// Sesión de edición: decodifica un RAW (LibRaw) o un JPEG/PNG (stb_image) y exporta JPEG. Sin dependencias de Android.
#pragma once
#include <cstdint>
#include <memory>
#include <string>
#include <vector>

#include "develop.h"

namespace rp {

constexpr int kErrBadArgs = -1001;
constexpr int kErrUnsupportedImage = -1002;
constexpr int kErrBitmap = -1003;
constexpr int kErrWrite = -1004;

struct Session {
    std::string path;
    std::vector<uint16_t> preview;  // RGB16 lineal, orientación aplicada
    int pw = 0, ph = 0;
    std::vector<uint16_t> small;  // vista previa reducida (~512 px) para histograma y análisis
    int sw = 0, sh = 0;
    Aux aux;  // capas de los efectos con vecinos para la vista previa (se calculan al primer uso)
    std::string info;  // make|model|iso|shutter|aperture|focal|fullW|fullH
};

// Abre el RAW y prepara la vista previa (media resolución + reducción a previewLongEdge).
// Devuelve 0 o un código de error (<0 propio, o -código de LibRaw).
int openSession(const std::string& path, int previewLongEdge, std::unique_ptr<Session>& out);

// Revela a resolución completa (o limitada a maxEdge px si > 0) y escribe un JPEG.
int exportJpeg(const Session& s, const Params& p, const std::string& outPath, int quality,
               int maxEdge);

constexpr int kFormatJpeg = 0;
constexpr int kFormatPng = 1;
// Igual, eligiendo el formato (JPEG con `quality`, o PNG sin pérdida).
int exportImage(const Session& s, const Params& p, const std::string& outPath, int quality,
                int maxEdge, int format);

// Calcula (una vez) las capas auxiliares de la vista previa.
void ensureAux(Session& s);

// Miniatura JPEG (lado largo = edge) de la vista previa sin editar, para la pantalla de inicio.
int exportThumbnail(const Session& s, const std::string& outPath, int edge, int quality);

// Texto legible para un código devuelto por las funciones anteriores.
std::string errorText(int code);

int workerThreads();

}  // namespace rp
