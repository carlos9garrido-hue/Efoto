package com.rawpocket

import android.graphics.Bitmap

/**
 * Puente hacia el núcleo nativo (LibRaw + motor de revelado).
 * Los valores de retorno negativos de [nativeOpen] y [nativeExport] son códigos de error;
 * usa [nativeErrorText] para obtener el mensaje.
 */
object RawNative {
    init {
        System.loadLibrary("rawpocket")
    }

    /** Abre un RAW, JPG o PNG y prepara la vista previa. Devuelve un handle (> 0) o un código de error (< 0). */
    external fun nativeOpen(path: String, previewLongEdge: Int): Long

    external fun nativePreviewWidth(handle: Long): Int
    external fun nativePreviewHeight(handle: Long): Int

    /** "marca|modelo|iso|obturador|apertura|focal|ancho|alto" */
    external fun nativeInfo(handle: Long): String

    /** Guarda una miniatura JPEG (lado largo [edge]) de la foto sin editar. Devuelve 0 o un error. */
    external fun nativeThumbnail(handle: Long, outPath: String, edge: Int): Int

    /** Histograma 4x256 (R, G, B, luminancia) de la foto revelada con [params]. */
    external fun nativeHistogram(handle: Long, params: FloatArray, out: IntArray): Boolean

    /** Ajuste automático: out = exposición, contraste, luces, sombras, blancos, negros. */
    external fun nativeAuto(handle: Long, out: FloatArray): Boolean

    /** Cuentagotas: (u, v) sobre la imagen girada y recortada -> out = temperatura, matiz. */
    external fun nativeSampleWb(handle: Long, params: FloatArray, u: Float, v: Float, out: FloatArray): Boolean

    external fun nativeErrorText(code: Int): String

    /**
     * Copia la vista previa a un ByteBuffer directo como RGBA half-float (textura GL_RGBA16F).
     * El búfer necesita ancho * alto * 8 bytes.
     */
    external fun nativeFillPreviewHalf(handle: Long, buffer: java.nio.ByteBuffer): Boolean

    /** Revela la vista previa dentro de [bitmap] (ARGB_8888, mismo tamaño que la vista previa). */
    external fun nativeRender(handle: Long, params: FloatArray, bitmap: Bitmap): Boolean

    /** Exporta un JPEG. [maxEdge] = 0 para resolución completa. Devuelve 0 o un código de error. */
    external fun nativeExport(handle: Long, params: FloatArray, outPath: String, quality: Int, maxEdge: Int): Int

    external fun nativeClose(handle: Long)
}
