package com.rawpocket

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs
import kotlin.math.sqrt

/** Acciones sobre las máscaras de edición local. */
class LocalActions(
    val onAdd: (Int) -> Int,
    val onRemove: (Int) -> Unit,
    val onChange: (Int, LocalMask, Int?) -> Unit,
)

// Identificadores de los puntos de control que se arrastran sobre la foto.
const val H_A = 0       // lineal: inicio (100 %) · radial: centro
const val H_B = 1       // lineal: fin (0 %) · radial: radio horizontal
const val H_C = 2       // radial: radio vertical
const val H_MID = 3     // lineal: mueve todo el degradado

/** Puntos de control de una máscara en coordenadas de imagen (0..1). */
fun maskHandles(m: LocalMask): List<Pair<Int, Pair<Float, Float>>> = when {
    m.isLinear -> listOf(
        H_A to (m.x1 to m.y1),
        H_B to (m.x2 to m.y2),
        H_MID to ((m.x1 + m.x2) / 2f to (m.y1 + m.y2) / 2f),
    )
    m.isRadial -> listOf(
        H_A to (m.x1 to m.y1),
        H_B to (m.x1 + m.x2 to m.y1),
        H_C to (m.x1 to m.y1 + m.y2),
    )
    else -> emptyList()
}

/** Máscara resultante de arrastrar el punto [handle] hasta (u, v). [prev] es la posición anterior del dedo. */
fun dragMask(m: LocalMask, handle: Int, u: Float, v: Float, du: Float, dv: Float): LocalMask = when {
    m.isLinear -> when (handle) {
        H_A -> m.copy(x1 = u, y1 = v)
        H_B -> m.copy(x2 = u, y2 = v)
        else -> m.copy(
            x1 = (m.x1 + du).coerceIn(-0.5f, 1.5f), y1 = (m.y1 + dv).coerceIn(-0.5f, 1.5f),
            x2 = (m.x2 + du).coerceIn(-0.5f, 1.5f), y2 = (m.y2 + dv).coerceIn(-0.5f, 1.5f),
        )
    }
    m.isRadial -> when (handle) {
        H_A -> m.copy(x1 = u, y1 = v)
        H_B -> m.copy(x2 = abs(u - m.x1).coerceIn(0.02f, 1.5f))
        else -> m.copy(y2 = abs(v - m.y1).coerceIn(0.02f, 1.5f))
    }
    else -> m
}

/** Dibuja la guía de la máscara sobre la foto. [toScreen] convierte (u, v) a píxeles. */
fun DrawScope.drawMaskGuide(m: LocalMask, toScreen: (Float, Float) -> Offset, color: Color, handleRadius: Float) {
    val stroke = Stroke(width = 1.6.dp.toPx())
    val shadow = Color.Black.copy(alpha = 0.45f)
    if (m.isLinear) {
        val a = toScreen(m.x1, m.y1)
        val b = toScreen(m.x2, m.y2)
        val dir = b - a
        val len = sqrt(dir.x * dir.x + dir.y * dir.y).coerceAtLeast(1f)
        val n = Offset(-dir.y / len, dir.x / len) * 4000f
        for ((p, alpha) in listOf(a to 1f, b to 0.55f)) {
            drawLine(shadow, p - n, p + n, strokeWidth = 3.4.dp.toPx(), cap = StrokeCap.Round)
            drawLine(color.copy(alpha = alpha), p - n, p + n, strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
        }
        drawLine(color.copy(alpha = 0.5f), a, b, strokeWidth = 1.dp.toPx())
    } else if (m.isRadial) {
        val c = toScreen(m.x1, m.y1)
        val rx = toScreen(m.x1 + m.x2, m.y1).x - c.x
        val ry = toScreen(m.x1, m.y1 + m.y2).y - c.y
        if (rx > 1f && ry > 1f) {
            val tl = Offset(c.x - rx, c.y - ry)
            val sz = Size(rx * 2f, ry * 2f)
            drawOval(shadow, tl, sz, style = Stroke(width = 3.4.dp.toPx()))
            drawOval(color, tl, sz, style = stroke)
            val inner = (1f - m.feather.coerceIn(0.02f, 1f)).coerceAtLeast(0.02f)
            drawOval(
                color.copy(alpha = 0.4f), Offset(c.x - rx * inner, c.y - ry * inner),
                Size(rx * inner * 2f, ry * inner * 2f), style = Stroke(width = 1.dp.toPx()),
            )
        }
    }
    for ((_, uv) in maskHandles(m)) {
        val p = toScreen(uv.first, uv.second)
        drawCircle(shadow, handleRadius + 1.5.dp.toPx(), p)
        drawCircle(color, handleRadius, p)
        drawCircle(Color.Black.copy(alpha = 0.55f), handleRadius * 0.4f, p)
    }
}

/** Panel de edición local: lista de máscaras, botones para crear y deslizadores de la máscara elegida. */
@Composable
fun LocalPanel(
    params: EditParams,
    selected: Int,
    onSelect: (Int) -> Unit,
    actions: LocalActions,
) {
    val active = (0 until EditParams.MASK_COUNT).filter { params.mask(it).isActive }
    Column(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val full = active.size >= EditParams.MASK_COUNT
            SoftButton("+ Degradado lineal", { onSelect(actions.onAdd(LocalMask.LINEAR)) }, enabled = !full)
            SoftButton("+ Radial", { onSelect(actions.onAdd(LocalMask.RADIAL)) }, enabled = !full)
        }
        if (active.isEmpty()) {
            Text(
                "Añade un degradado para aclarar u oscurecer solo una parte de la foto, " +
                    "y arrastra sus puntos sobre la imagen.",
                color = Palette.TextSecondary, fontSize = 12.sp,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                active.forEach { i ->
                    val m = params.mask(i)
                    SoftButton(
                        (if (m.isLinear) "Lineal " else "Radial ") + (i + 1),
                        { onSelect(i) },
                        accent = i == selected,
                    )
                }
            }
        }
        val m = if (selected in 0 until EditParams.MASK_COUNT) params.mask(selected) else LocalMask()
        if (m.isActive) {
            LocalMask.ADJUSTMENTS.forEachIndexed { k, adj ->
                LocalSliderRow(adj, m.adjust[k], selected, k, m, actions)
            }
            if (m.isRadial) {
                Text("Suavidad del borde", color = Palette.TextSecondary, fontSize = 12.sp)
                ElegantSlider(
                    value = m.feather,
                    onChange = { actions.onChange(selected, m.copy(feather = it), 310000 + selected * 10 + 8) },
                    range = 0.02f..1f,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SoftButton(
                    if (m.invert) "Invertida" else "Invertir",
                    { actions.onChange(selected, m.copy(invert = !m.invert), null) },
                    accent = m.invert,
                )
                SoftButton("Eliminar", { actions.onRemove(selected); onSelect(-1) })
            }
        }
    }
}

@Composable
private fun LocalSliderRow(adj: LocalAdjustment, value: Float, slot: Int, k: Int, m: LocalMask, actions: LocalActions) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(adj.label, color = Palette.TextSecondary, fontSize = 12.sp)
            Text(adj.format(value), color = if (value != 0f) Palette.Accent else Palette.TextSecondary, fontSize = 12.sp)
        }
        ElegantSlider(
            value = value,
            onChange = {
                val next = m.adjust.toMutableList().also { l -> l[k] = it }
                actions.onChange(slot, m.copy(adjust = next), 310000 + slot * 10 + k)
            },
            range = adj.min..adj.max,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
