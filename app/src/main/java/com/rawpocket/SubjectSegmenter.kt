package com.rawpocket

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.nio.FloatBuffer

/**
 * Detecta el sujeto principal de una foto con un modelo U²-Net pequeño (4,6 MB, Apache 2.0) que
 * corre en el propio teléfono con ONNX Runtime: sin internet y sin enviar nada a ningún servidor.
 * La preparación de la imagen y el refinado de los bordes están en C++ (segment.cpp).
 */
object SubjectSegmenter {
    private const val MODEL = "models/u2netp.onnx"
    private const val SIZE = 320

    private var env: OrtEnvironment? = null
    private var session: OrtSession? = null

    @Synchronized
    private fun session(context: Context): OrtSession {
        session?.let { return it }
        val e = OrtEnvironment.getEnvironment()
        val bytes = context.applicationContext.assets.open(MODEL).use { it.readBytes() }
        val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(4) }
        val s = e.createSession(bytes, options)
        env = e
        session = s
        return s
    }

    /** [input] = 3 x 320 x 320 floats. Devuelve 320 x 320 valores (0..1 aproximadamente) o null si falla. */
    @Synchronized
    fun run(context: Context, input: FloatArray): FloatArray? = try {
        val s = session(context)
        val e = env!!
        OnnxTensor.createTensor(e, FloatBuffer.wrap(input), longArrayOf(1, 3, SIZE.toLong(), SIZE.toLong())).use { tensor ->
            s.run(mapOf(s.inputNames.first() to tensor)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val rows = (result.get(0).value as Array<Array<Array<FloatArray>>>)[0][0]
                val out = FloatArray(SIZE * SIZE)
                for (y in 0 until SIZE) System.arraycopy(rows[y], 0, out, y * SIZE, SIZE)
                out
            }
        }
    } catch (t: Throwable) {
        android.util.Log.e("Efoto", "Falló la selección de sujeto", t)
        null
    }
}
