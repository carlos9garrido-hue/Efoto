// Entrada de imágenes JPEG/PNG (sin dependencias de Android): decodifica con stb_image, lee la
// orientación y los datos de cámara del EXIF y entrega RGB lineal de 16 bits, igual que los RAW.
#pragma once
#include <cstddef>
#include <cstdint>
#include <string>
#include <vector>

namespace rp {

constexpr int kErrImageDecode = -1005;
constexpr int kErrImageRead = -1006;

struct ExifInfo {
    std::string make, model;
    double iso = 0, shutter = 0, aperture = 0, focal = 0;
    int orientation = 1;  // 1..8, como en EXIF
};

// ¿Los primeros bytes son de un JPEG (FF D8 FF) o de un PNG?
bool looksLikeJpegOrPng(const uint8_t* d, size_t n);

// Lee EXIF (segmento APP1) de un JPEG en memoria. Es tolerante a archivos dañados: nunca lee
// fuera del búfer. Devuelve false si no hay EXIF utilizable.
bool parseJpegExif(const uint8_t* d, size_t n, ExifInfo& out);

// Reordena píxeles RGB de 8 bits según la orientación EXIF (1..8). Los valores fuera de rango
// se tratan como 1. ow/oh son las dimensiones resultantes (se intercambian en 5..8).
void applyExifOrientation(const uint8_t* rgb, int w, int h, int orientation,
                          std::vector<uint8_t>& out, int& ow, int& oh);

// Decodifica un JPEG/PNG de disco a RGB16 lineal (sRGB decodificado), con la orientación aplicada.
// Devuelve 0 o un código de error (<0).
int decodeImageFile(const std::string& path, std::vector<uint16_t>& rgb16, int& w, int& h,
                    ExifInfo& info);

}  // namespace rp
