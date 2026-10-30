package com.rawpocket

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import java.nio.ByteBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Vista previa lista para la GPU: RGBA half-float lineal (8 bytes por píxel) en un búfer directo.
 * Es independiente de la sesión nativa, así que puede vivir en el hilo de GL sin riesgo de carreras.
 */
class GpuPhoto(val width: Int, val height: Int, val data: ByteBuffer)

/**
 * Dibuja la foto aplicando el revelado en un fragment shader (assets/shaders/develop.frag).
 * El shader replica Developer::pixel() de C++, que es lo que usa la exportación a resolución completa.
 */
class DevelopRenderer(private val context: Context, private val view: PreviewView) : GLSurfaceView.Renderer {

    /** Se asignan desde el hilo principal y se leen en el hilo de GL. */
    @Volatile var photo: GpuPhoto? = null
    @Volatile var params: FloatArray = FloatArray(PARAM_COUNT)

    private var program = 0
    private var texture = 0
    private var paramsLocation = -1
    private var rotLocation = -1
    private var uploaded: GpuPhoto? = null
    private var surfaceWidth = 1
    private var surfaceHeight = 1

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // Si el contexto se pierde (o se recrea) hay que volver a subir la textura.
        uploaded = null
        program = buildProgram("shaders/develop.vert", "shaders/develop.frag")
        if (program == 0) return

        paramsLocation = GLES30.glGetUniformLocation(program, "uParams")
        rotLocation = GLES30.glGetUniformLocation(program, "uRot")
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        texture = tex[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glUseProgram(program)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uTex"), 0)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClearColor(BG, BG, BG + 2f / 255f, 1f)  // #0E0E10, igual que Palette.Background
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)

        val p = photo ?: return
        if (program == 0) return

        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texture)
        if (p !== uploaded) {
            p.data.position(0)
            GLES30.glTexImage2D(
                GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, p.width, p.height, 0,
                GLES30.GL_RGBA, GLES30.GL_HALF_FLOAT, p.data,
            )
            uploaded = p
        }

        // La imagen final es la foto girada y recortada: su tamaño (como en outputSize() de C++)
        // decide el encuadre. Ajuste "contain": entera, centrada, sin deformarse; el zoom y el
        // desplazamiento agrandan y mueven ese rectángulo (lo que sobresale lo recorta la pantalla).
        val values = params
        val turns = Math.floor(values[ANGLE_INDEX + 1] + 0.5).toInt() and 3
        val frameW = (if (turns and 1 == 1) p.height else p.width).toFloat()
        val frameH = (if (turns and 1 == 1) p.width else p.height).toFloat()
        fun crop(i: Int) = values[ANGLE_INDEX + 2 + i].coerceIn(0f, 1f)
        val outW = (frameW * (1f - crop(0) - crop(2))).coerceAtLeast(1f)
        val outH = (frameH * (1f - crop(1) - crop(3))).coerceAtLeast(1f)
        val scale = min(surfaceWidth / outW, surfaceHeight / outH)
        val w = (outW * scale).roundToInt().coerceIn(1, surfaceWidth)
        val h = (outH * scale).roundToInt().coerceIn(1, surfaceHeight)
        view.surfW = surfaceWidth
        view.surfH = surfaceHeight
        view.fitW = w.toFloat()
        view.fitH = h.toFloat()
        view.clampPan()
        val zoom = view.zoom
        val vw = (w * zoom).roundToInt().coerceAtLeast(1)
        val vh = (h * zoom).roundToInt().coerceAtLeast(1)
        val vx = (surfaceWidth / 2f + view.panX - vw / 2f).roundToInt()
        val vy = (surfaceHeight - (surfaceHeight / 2f + view.panY) - vh / 2f).roundToInt()
        GLES30.glViewport(vx, vy, vw, vh)

        GLES30.glUseProgram(program)
        // cos/sin del enderezado en doble precisión (la GPU solo garantiza 2^-11 de error)
        val angle = Math.toRadians(values[ANGLE_INDEX].toDouble())
        GLES30.glUniform2f(rotLocation, Math.cos(angle).toFloat(), Math.sin(angle).toFloat())

        if (view.splitOn) {
            // Comparación dividida: a la izquierda la foto sin ajustes (con la misma geometría),
            // a la derecha la editada. Cada mitad se dibuja recortada con scissor.
            val original = values.copyOf().also { for (i in 0 until ANGLE_INDEX) it[i] = 0f }
            val divX = (vx + view.splitFrac * vw).roundToInt().coerceIn(0, surfaceWidth)
            GLES30.glEnable(GLES30.GL_SCISSOR_TEST)
            GLES30.glScissor(0, 0, divX, surfaceHeight)
            GLES30.glUniform1fv(paramsLocation, PARAM_COUNT, original, 0)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            GLES30.glScissor(divX, 0, surfaceWidth - divX, surfaceHeight)
            GLES30.glUniform1fv(paramsLocation, PARAM_COUNT, values, 0)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
            // Línea divisoria de 3 px.
            GLES30.glScissor((divX - 1).coerceAtLeast(0), 0, 3, surfaceHeight)
            GLES30.glClearColor(1f, 1f, 1f, 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glDisable(GLES30.GL_SCISSOR_TEST)
        } else {
            GLES30.glUniform1fv(paramsLocation, PARAM_COUNT, values, 0)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }
    }

    // ---------------------------------------------------------------- shaders

    private fun buildProgram(vertexAsset: String, fragmentAsset: String): Int {
        val vs = compile(GLES30.GL_VERTEX_SHADER, readAsset(vertexAsset))
        val fs = compile(GLES30.GL_FRAGMENT_SHADER, readAsset(fragmentAsset))
        if (vs == 0 || fs == 0) return 0
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, ok, 0)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        if (ok[0] == 0) {
            Log.e(TAG, "Error al enlazar el programa: ${GLES30.glGetProgramInfoLog(prog)}")
            GLES30.glDeleteProgram(prog)
            return 0
        }
        return prog
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.e(TAG, "Error al compilar el shader: ${GLES30.glGetShaderInfoLog(shader)}")
            GLES30.glDeleteShader(shader)
            return 0
        }
        return shader
    }

    private fun readAsset(name: String): String =
        context.assets.open(name).bufferedReader().use { it.readText() }

    private companion object {
        const val TAG = "RawPocketGL"
        const val PARAM_COUNT = 45  // igual que Params::kCount en develop.h
        const val ANGLE_INDEX = 39  // Params::kGeomStart: enderezado, giros, recorte L/T/R/B
        const val BG = 0x0E / 255f
    }
}
