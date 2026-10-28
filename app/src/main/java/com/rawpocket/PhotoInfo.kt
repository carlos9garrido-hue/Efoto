package com.rawpocket

import java.util.Locale
import kotlin.math.roundToInt

/** Metadatos legibles de la foto, a partir de la cadena que devuelve el núcleo nativo. */
data class PhotoInfo(
    val camera: String,
    val exposureLine: String,
    val sizeLine: String,
) {
    companion object {
        /** Formato: "marca|modelo|iso|obturador|apertura|focal|ancho|alto" */
        fun parse(raw: String): PhotoInfo {
            val f = raw.split('|')
            fun d(i: Int) = f.getOrNull(i)?.toDoubleOrNull() ?: 0.0

            val make = f.getOrNull(0).orEmpty().trim()
            val model = f.getOrNull(1).orEmpty().trim()
            val camera = when {
                model.startsWith(make, ignoreCase = true) -> model
                else -> "$make $model".trim()
            }

            val parts = mutableListOf<String>()
            if (d(2) > 0) parts += "ISO ${d(2).roundToInt()}"
            val shutter = d(3)
            if (shutter > 0) {
                parts += if (shutter < 0.5) "1/${(1.0 / shutter).roundToInt()} s"
                else String.format(Locale.US, "%.1f s", shutter)
            }
            if (d(4) > 0) parts += String.format(Locale.US, "f/%.1f", d(4))
            if (d(5) > 0) parts += "${d(5).roundToInt()} mm"

            val w = d(6).toInt()
            val h = d(7).toInt()
            val size = if (w > 0 && h > 0)
                String.format(Locale.US, "%d × %d · %.1f MP", w, h, w.toLong() * h / 1_000_000.0)
            else ""

            return PhotoInfo(camera, parts.joinToString(" · "), size)
        }
    }
}
