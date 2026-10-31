package com.rawpocket

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import kotlin.math.max
import kotlin.math.min

/**
 * Estado de la vista de la foto: zoom, desplazamiento y comparación dividida. Lo escribe la UI
 * (gestos) y lo lee el hilo de GL al dibujar, por eso los campos son @Volatile.
 * Coordenadas de pantalla con origen arriba a la izquierda; [panX]/[panY] desplazan la imagen en píxeles.
 */
class PreviewView {
    @Volatile var zoom = 1f
        private set
    @Volatile var panX = 0f
        private set
    @Volatile var panY = 0f
        private set
    @Volatile var splitOn = false
    @Volatile var splitFrac = 0.5f

    /** Tamaño de la superficie y de la foto ajustada (sin zoom); los fija el renderizador. */
    @Volatile var surfW = 1
    @Volatile var surfH = 1
    @Volatile var fitW = 1f
    @Volatile var fitH = 1f

    /** Sube cada vez que cambia el encuadre (zoom, desplazamiento o tamaño): las capas dibujadas encima lo leen. */
    var tick by mutableIntStateOf(0)
        private set

    fun bump() { tick += 1 }

    /** Posición (0..1, 0..1) sobre la imagen mostrada -> punto de pantalla. */
    fun toScreen(u: Float, v: Float): Offset {
        val vw = fitW * zoom
        val vh = fitH * zoom
        return Offset(surfW / 2f + panX - vw / 2f + u * vw, surfH / 2f + panY - vh / 2f + v * vh)
    }

    /** Punto de pantalla -> posición sobre la imagen, recortada a 0..1. */
    fun toImageUvClamped(x: Float, y: Float): Pair<Float, Float> {
        val vw = max(1f, fitW * zoom)
        val vh = max(1f, fitH * zoom)
        val u = (x - (surfW / 2f + panX - vw / 2f)) / vw
        val v = (y - (surfH / 2f + panY - vh / 2f)) / vh
        return u.coerceIn(0f, 1f) to v.coerceIn(0f, 1f)
    }

    /** La UI lo cambia por algo que pide un nuevo fotograma. */
    @Volatile var invalidate: () -> Unit = {}

    fun maxZoom(): Float = max(1f, min(8f, MAX_VIEWPORT / max(fitW, fitH)))

    fun reset() {
        zoom = 1f; panX = 0f; panY = 0f
        bump()
        invalidate()
    }

    /** Zoom (factor) alrededor del punto (cx, cy) de pantalla y desplazamiento (dx, dy). */
    fun transform(cx: Float, cy: Float, zoomChange: Float, dx: Float, dy: Float) {
        val newZoom = (zoom * zoomChange).coerceIn(1f, maxZoom())
        val k = newZoom / zoom
        val centerX = surfW / 2f + panX
        val centerY = surfH / 2f + panY
        val nx = cx - (cx - centerX) * k + dx
        val ny = cy - (cy - centerY) * k + dy
        zoom = newZoom
        panX = nx - surfW / 2f
        panY = ny - surfH / 2f
        clampPan()
        bump()
        invalidate()
    }

    /** Mantiene la foto cubriendo la pantalla cuando está ampliada y centrada cuando no. */
    fun clampPan() {
        val limX = max(0f, (fitW * zoom - surfW) / 2f)
        val limY = max(0f, (fitH * zoom - surfH) / 2f)
        panX = panX.coerceIn(-limX, limX)
        panY = panY.coerceIn(-limY, limY)
    }

    /** Punto de pantalla -> posición (0..1, 0..1) sobre la imagen mostrada, o null si cae fuera. */
    fun toImageUv(x: Float, y: Float): Pair<Float, Float>? {
        val vw = fitW * zoom
        val vh = fitH * zoom
        val left = surfW / 2f + panX - vw / 2f
        val top = surfH / 2f + panY - vh / 2f
        val u = (x - left) / vw
        val v = (y - top) / vh
        return if (u in 0f..1f && v in 0f..1f) u to v else null
    }

    private companion object {
        const val MAX_VIEWPORT = 8192f
    }
}
