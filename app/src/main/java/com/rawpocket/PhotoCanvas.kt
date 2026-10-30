package com.rawpocket

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sqrt

/** Acciones de las herramientas de edición que no son deslizadores. */
class ToolActions(
    val onUndo: () -> Unit,
    val onRedo: () -> Unit,
    val onAuto: () -> Unit,
    val onPickWb: (Float, Float) -> Unit,
    val onResetGroup: (Group) -> Unit,
)

/**
 * La foto con sus herramientas: zoom (pellizcar o doble toque), desplazamiento, comparación
 * dividida, mantener pulsado "Original", histograma, deshacer/rehacer y cuentagotas.
 */
@Composable
fun PhotoCanvas(
    state: EditorViewModel.UiState,
    gpuParams: FloatArray,
    tools: ToolActions,
    picking: Boolean,
    onPickingDone: () -> Unit,
    onHoldOriginal: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val view = remember { PreviewView() }
    LaunchedEffect(state.photo) { view.reset() }
    var split by rememberSaveable { mutableStateOf(false) }
    var showHistogram by rememberSaveable { mutableStateOf(true) }
    LaunchedEffect(split) {
        view.splitOn = split
        view.invalidate()
    }
    val pickingNow by rememberUpdatedState(picking)

    Box(modifier, contentAlignment = Alignment.Center) {
        GpuPreview(photo = state.photo, params = gpuParams, view = view, modifier = Modifier.fillMaxSize())

        // Capa transparente de gestos: la SurfaceView de GL no debe tragarse los toques.
        Box(
            Modifier
                .fillMaxSize()
                .photoGestures(view) { pos ->
                    if (pickingNow) {
                        view.toImageUv(pos.x, pos.y)?.let { (u, v) -> tools.onPickWb(u, v) }
                        onPickingDone()
                    }
                }
        )

        if (state.showOriginal) {
            Chip("ORIGINAL", Modifier.align(Alignment.TopCenter).padding(top = 8.dp))
        } else if (picking) {
            Chip("Toca un punto gris o blanco", Modifier.align(Alignment.TopCenter).padding(top = 8.dp), onClick = onPickingDone)
        }

        state.histogram?.let { h ->
            if (showHistogram) {
                Histogram(
                    h,
                    Modifier
                        .align(Alignment.TopEnd)
                        .padding(8.dp)
                        .size(width = 112.dp, height = 52.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Palette.Background.copy(alpha = 0.6f))
                        .clickable { showHistogram = false },
                )
            } else {
                Chip("Histograma", Modifier.align(Alignment.TopEnd).padding(8.dp), onClick = { showHistogram = true })
            }
        }

        Row(
            Modifier
                .align(Alignment.BottomStart)
                .padding(8.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        ) {
            IconChip(ToolIcon.UNDO, enabled = state.canUndo, onClick = tools.onUndo)
            IconChip(ToolIcon.REDO, enabled = state.canRedo, onClick = tools.onRedo)
        }
        Row(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(8.dp),
            horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
        ) {
            IconChip(ToolIcon.SPLIT, selected = split, onClick = { split = !split })
            Chip("Original", modifier = Modifier.holdToCompareChip(onHoldOriginal))
        }
    }
}

@Composable
private fun IconChip(icon: ToolIcon, enabled: Boolean = true, selected: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(if (selected) Palette.Accent.copy(alpha = 0.85f) else Palette.Background.copy(alpha = 0.7f))
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { IconGlyph(icon, if (selected) Color(0xFF1B1405) else Palette.TextPrimary.copy(alpha = if (enabled) 1f else 0.35f), Modifier.size(20.dp)) }
}

@Composable
private fun Chip(
    text: String,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    selected: Boolean = false,
    onClick: (() -> Unit)? = null,
) {
    val base = modifier
        .clip(RoundedCornerShape(50))
        .background(if (selected) Palette.Accent.copy(alpha = 0.85f) else Palette.Background.copy(alpha = 0.7f))
        .alpha(if (enabled) 1f else 0.35f)
    val clickable = if (onClick != null && enabled) base.clickable(onClick = onClick) else base
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = if (selected) Color(0xFF1B1405) else Palette.TextPrimary,
        modifier = clickable.padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** Mientras el dedo está sobre el botón se notifica `true`; al soltarlo, `false`. */
private fun Modifier.holdToCompareChip(onHold: (Boolean) -> Unit): Modifier = this.then(
    Modifier.pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            onHold(true)
            waitForUpOrCancellation()
            onHold(false)
        }
    }
)

/**
 * Gestos de la foto: dos dedos = zoom y desplazamiento; un dedo = desplazamiento si hay zoom (o
 * arrastrar la línea divisoria de la comparación); doble toque = acercar/ajustar; toque = [onTap].
 */
private fun Modifier.photoGestures(view: PreviewView, onTap: (Offset) -> Unit): Modifier =
    pointerInput(view) {
        var lastTapTime = 0L
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val slop = viewConfiguration.touchSlop
            val grab = 48.dp.toPx()
            // ¿Empezó sobre la línea divisoria?
            val vw = view.fitW * view.zoom
            val dividerX = view.surfW / 2f + view.panX - vw / 2f + view.splitFrac * vw
            val onDivider = view.splitOn && abs(down.position.x - dividerX) < grab
            var moved = false
            do {
                val event = awaitPointerEvent()
                val pressed = event.changes.filter { it.pressed }
                if (pressed.size >= 2) {
                    moved = true
                    val z = event.calculateZoom()
                    val pan = event.calculatePan()
                    val c = event.calculateCentroid()
                    if (z != 1f || pan != Offset.Zero) view.transform(c.x, c.y, z, pan.x, pan.y)
                    event.changes.forEach { it.consume() }
                } else if (pressed.size == 1) {
                    val ch = pressed[0]
                    val d = ch.positionChange()
                    if (!moved && (ch.position - down.position).getDistance() > slop) moved = true
                    if (moved && d != Offset.Zero) {
                        if (onDivider) {
                            val width = max(1f, view.fitW * view.zoom)
                            val left = view.surfW / 2f + view.panX - width / 2f
                            view.splitFrac = ((ch.position.x - left) / width).coerceIn(0.02f, 0.98f)
                            view.invalidate()
                        } else if (view.zoom > 1f) {
                            view.transform(ch.position.x, ch.position.y, 1f, d.x, d.y)
                        }
                        ch.consume()
                    }
                }
            } while (event.changes.any { it.pressed })

            if (!moved) {
                val now = down.uptimeMillis
                if (now - lastTapTime < 300) {
                    if (view.zoom > 1.01f) view.reset()
                    else view.transform(down.position.x, down.position.y, 2.5f, 0f, 0f)
                    lastTapTime = 0L
                } else {
                    lastTapTime = now
                    onTap(down.position)
                }
            }
        }
    }

/** R, G y B apilados con transparencia y la luminancia como línea. Escala de raíz cuadrada. */
@Composable
private fun Histogram(bins: IntArray, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        if (bins.size < 1024) return@Canvas
        // El máximo ignora los extremos (negros/blancos recortados), que si no aplastan todo.
        var peak = 1
        for (c in 0 until 4) for (i in 1..254) peak = max(peak, bins[c * 256 + i])
        val w = size.width
        val h = size.height - 4f
        fun y(v: Int) = h - sqrt((v.toFloat() / peak).coerceAtMost(1f)) * (h - 4f) + 2f
        val colors = listOf(Color(0xFFFF5A4D), Color(0xFF5ACB6B), Color(0xFF4D8DFF))
        for (c in 0 until 3) {
            val path = Path().apply {
                moveTo(0f, h + 2f)
                for (i in 0 until 256) lineTo(i / 255f * w, y(bins[c * 256 + i]))
                lineTo(w, h + 2f)
                close()
            }
            drawPath(path, colors[c].copy(alpha = 0.45f))
        }
        val line = Path().apply {
            moveTo(0f, y(bins[768]))
            for (i in 1 until 256) lineTo(i / 255f * w, y(bins[768 + i]))
        }
        drawPath(line, Color.White.copy(alpha = 0.85f), style = Stroke(width = 1.2f))
    }
}
