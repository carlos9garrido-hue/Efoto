package com.rawpocket

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

// ------------------------------------------------------------------ iconos dibujados a mano

/** Iconos de línea fina, dibujados con trazos para no depender de un paquete de iconos. */
enum class ToolIcon { LIGHT, COLOR, DETAIL, CURVE, HSL, GRADE, EFFECTS, CROP, PRESETS, BACK, UNDO, REDO, SPLIT, PLUS, CHECK, CLOSE }

fun Group.icon(): ToolIcon = when (this) {
    Group.LIGHT -> ToolIcon.LIGHT
    Group.COLOR -> ToolIcon.COLOR
    Group.DETAIL -> ToolIcon.DETAIL
    Group.CURVE -> ToolIcon.CURVE
    Group.HSL -> ToolIcon.HSL
    Group.GRADE -> ToolIcon.GRADE
    Group.EFFECTS -> ToolIcon.EFFECTS
    Group.CROP -> ToolIcon.CROP
}

@Composable
fun IconGlyph(icon: ToolIcon, color: Color, modifier: Modifier = Modifier.size(22.dp)) {
    Canvas(modifier) { drawGlyph(icon, color) }
}

private fun DrawScope.drawGlyph(icon: ToolIcon, color: Color) {
    val s = size.minDimension
    val w = s * 0.075f
    val stroke = Stroke(width = w, cap = StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
    fun p(x: Float, y: Float) = Offset(x * s, y * s)
    when (icon) {
        ToolIcon.LIGHT -> {
            drawCircle(color, radius = s * 0.18f, center = p(0.5f, 0.5f), style = stroke)
            for (i in 0 until 8) {
                val a = Math.toRadians(i * 45.0)
                val c = cos(a).toFloat(); val sn = sin(a).toFloat()
                drawLine(color, p(0.5f + c * 0.30f, 0.5f + sn * 0.30f), p(0.5f + c * 0.42f, 0.5f + sn * 0.42f), strokeWidth = w, cap = StrokeCap.Round)
            }
        }
        ToolIcon.COLOR -> {
            val path = Path().apply {
                moveTo(s * 0.5f, s * 0.12f)
                cubicTo(s * 0.5f, s * 0.12f, s * 0.2f, s * 0.46f, s * 0.2f, s * 0.64f)
                cubicTo(s * 0.2f, s * 0.82f, s * 0.34f, s * 0.9f, s * 0.5f, s * 0.9f)
                cubicTo(s * 0.66f, s * 0.9f, s * 0.8f, s * 0.82f, s * 0.8f, s * 0.64f)
                cubicTo(s * 0.8f, s * 0.46f, s * 0.5f, s * 0.12f, s * 0.5f, s * 0.12f)
                close()
            }
            drawPath(path, color, style = stroke)
        }
        ToolIcon.DETAIL -> {
            val path = Path().apply {
                moveTo(s * 0.5f, s * 0.14f); lineTo(s * 0.86f, s * 0.82f); lineTo(s * 0.14f, s * 0.82f); close()
            }
            drawPath(path, color, style = stroke)
            drawLine(color, p(0.5f, 0.14f), p(0.5f, 0.82f), strokeWidth = w * 0.8f, cap = StrokeCap.Round)
        }
        ToolIcon.CURVE -> {
            drawRect(color.copy(alpha = 0.35f), topLeft = p(0.14f, 0.14f), size = Size(s * 0.72f, s * 0.72f), style = Stroke(width = w * 0.6f))
            val path = Path().apply {
                moveTo(s * 0.14f, s * 0.86f)
                cubicTo(s * 0.5f, s * 0.86f, s * 0.5f, s * 0.14f, s * 0.86f, s * 0.14f)
            }
            drawPath(path, color, style = stroke)
        }
        ToolIcon.HSL -> {
            drawCircle(color, radius = s * 0.17f, center = p(0.5f, 0.34f), style = stroke)
            drawCircle(color, radius = s * 0.17f, center = p(0.34f, 0.64f), style = stroke)
            drawCircle(color, radius = s * 0.17f, center = p(0.66f, 0.64f), style = stroke)
        }
        ToolIcon.GRADE -> {
            drawCircle(color, radius = s * 0.34f, center = p(0.5f, 0.5f), style = stroke)
            drawArc(color, 90f, 180f, true, topLeft = p(0.16f, 0.16f), size = Size(s * 0.68f, s * 0.68f), alpha = 0.45f)
        }
        ToolIcon.EFFECTS -> {
            val path = Path().apply {
                moveTo(s * 0.5f, s * 0.1f)
                quadraticBezierTo(s * 0.54f, s * 0.46f, s * 0.9f, s * 0.5f)
                quadraticBezierTo(s * 0.54f, s * 0.54f, s * 0.5f, s * 0.9f)
                quadraticBezierTo(s * 0.46f, s * 0.54f, s * 0.1f, s * 0.5f)
                quadraticBezierTo(s * 0.46f, s * 0.46f, s * 0.5f, s * 0.1f)
                close()
            }
            drawPath(path, color, style = stroke)
        }
        ToolIcon.CROP -> {
            drawLine(color, p(0.3f, 0.1f), p(0.3f, 0.7f), strokeWidth = w, cap = StrokeCap.Round)
            drawLine(color, p(0.3f, 0.7f), p(0.9f, 0.7f), strokeWidth = w, cap = StrokeCap.Round)
            drawLine(color, p(0.7f, 0.9f), p(0.7f, 0.3f), strokeWidth = w, cap = StrokeCap.Round)
            drawLine(color, p(0.7f, 0.3f), p(0.1f, 0.3f), strokeWidth = w, cap = StrokeCap.Round)
        }
        ToolIcon.PRESETS -> {
            for (i in 0 until 3) {
                val y = 0.26f + i * 0.24f
                drawLine(color, p(0.14f, y), p(0.86f, y), strokeWidth = w, cap = StrokeCap.Round)
                val x = listOf(0.34f, 0.64f, 0.46f)[i]
                drawCircle(color, radius = s * 0.07f, center = p(x, y))
            }
        }
        ToolIcon.BACK -> {
            val path = Path().apply { moveTo(s * 0.62f, s * 0.18f); lineTo(s * 0.3f, s * 0.5f); lineTo(s * 0.62f, s * 0.82f) }
            drawPath(path, color, style = stroke)
        }
        ToolIcon.UNDO, ToolIcon.REDO -> {
            val flip = icon == ToolIcon.REDO
            fun fx(x: Float) = if (flip) 1f - x else x
            val path = Path().apply {
                moveTo(s * fx(0.3f), s * 0.2f); lineTo(s * fx(0.16f), s * 0.36f); lineTo(s * fx(0.34f), s * 0.5f)
            }
            drawPath(path, color, style = stroke)
            val arc = Path().apply {
                moveTo(s * fx(0.16f), s * 0.36f)
                cubicTo(s * fx(0.6f), s * 0.3f, s * fx(0.86f), s * 0.46f, s * fx(0.8f), s * 0.68f)
                cubicTo(s * fx(0.76f), s * 0.82f, s * fx(0.6f), s * 0.86f, s * fx(0.46f), s * 0.84f)
            }
            drawPath(arc, color, style = stroke)
        }
        ToolIcon.SPLIT -> {
            drawRect(color, topLeft = p(0.14f, 0.2f), size = Size(s * 0.72f, s * 0.6f), style = stroke)
            drawLine(color, p(0.5f, 0.2f), p(0.5f, 0.8f), strokeWidth = w, cap = StrokeCap.Round)
        }
        ToolIcon.PLUS -> {
            drawLine(color, p(0.5f, 0.2f), p(0.5f, 0.8f), strokeWidth = w, cap = StrokeCap.Round)
            drawLine(color, p(0.2f, 0.5f), p(0.8f, 0.5f), strokeWidth = w, cap = StrokeCap.Round)
        }
        ToolIcon.CHECK -> {
            val path = Path().apply { moveTo(s * 0.2f, s * 0.54f); lineTo(s * 0.42f, s * 0.74f); lineTo(s * 0.8f, s * 0.28f) }
            drawPath(path, color, style = stroke)
        }
        ToolIcon.CLOSE -> {
            drawLine(color, p(0.24f, 0.24f), p(0.76f, 0.76f), strokeWidth = w, cap = StrokeCap.Round)
            drawLine(color, p(0.76f, 0.24f), p(0.24f, 0.76f), strokeWidth = w, cap = StrokeCap.Round)
        }
    }
}

/** Botón redondo con icono, para la barra superior y las herramientas sobre la foto. */
@Composable
fun IconAction(
    icon: ToolIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    tint: Color = Palette.TextPrimary,
) {
    Box(
        modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { IconGlyph(icon, if (enabled) tint else tint.copy(alpha = 0.3f)) }
}

// ------------------------------------------------------------------ barra de herramientas

/** Fila de herramientas con icono y nombre; la seleccionada lleva el acento y un punto debajo. */
@Composable
fun ToolRail(
    tabs: List<Pair<ToolIcon, String>>,
    selected: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp)) {
        tabs.forEachIndexed { i, (icon, label) ->
            val on = i == selected
            val tint = if (on) Palette.Accent else Palette.TextSecondary
            Column(
                Modifier
                    .width(68.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .clickable { onSelect(i) }
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                IconGlyph(icon, tint)
                Spacer(Modifier.height(3.dp))
                Text(label, color = tint, fontSize = 11.sp, maxLines = 1, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Box(
                    Modifier
                        .size(width = if (on) 16.dp else 4.dp, height = 3.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (on) Palette.Accent else Color.Transparent)
                )
            }
        }
    }
}

/** Texto pequeño en mayúsculas para títulos de sección. */
@Composable
fun Overline(text: String, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.bodySmall.copy(letterSpacing = 1.4.sp),
        color = Palette.TextSecondary,
        modifier = modifier,
    )
}

/** Botón de texto con borde fino, más discreto que un botón relleno. */
@Composable
fun SoftButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, accent: Boolean = false) {
    val color = if (accent) Palette.Accent else Palette.TextPrimary
    Text(
        text,
        color = if (enabled) color else color.copy(alpha = 0.35f),
        fontSize = 13.sp,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, if (accent && enabled) Palette.Accent.copy(alpha = 0.6f) else Palette.Outline, RoundedCornerShape(50))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
}

// ------------------------------------------------------------------ deslizador

/**
 * Deslizador fino: la parte activa sale del centro si el rango es de -x a +x. Doble toque en el
 * nombre o en el valor lo restablece (lo gestiona quien lo usa).
 */
@Composable
fun ElegantSlider(value: Float, onChange: (Float) -> Unit, range: ClosedFloatingPointRange<Float>, modifier: Modifier = Modifier) {
    val bipolar = range.start < 0f
    val span = range.endInclusive - range.start
    Slider(
        value = value,
        onValueChange = onChange,
        valueRange = range,
        modifier = modifier.height(30.dp),
        thumb = {
            Box(
                Modifier
                    .size(18.dp)
                    .background(Palette.TextPrimary, CircleShape)
                    .border(3.dp, Palette.Surface, CircleShape)
            )
        },
        track = { st ->
            Canvas(Modifier.fillMaxWidth().height(4.dp)) {
                val frac = ((st.value - range.start) / span).coerceIn(0f, 1f)
                val origin = if (bipolar) -range.start / span else 0f
                val r = size.height / 2f
                drawLine(Palette.Outline, Offset(r, r), Offset(size.width - r, r), strokeWidth = size.height, cap = StrokeCap.Round)
                val a = minOf(frac, origin) * size.width
                val b = maxOf(frac, origin) * size.width
                if (b - a > 0.5f) drawLine(Palette.Accent, Offset(a.coerceAtLeast(r), r), Offset(b.coerceAtMost(size.width - r), r), strokeWidth = size.height, cap = StrokeCap.Round)
                if (bipolar) drawLine(Palette.TextSecondary, Offset(origin * size.width, -2f), Offset(origin * size.width, size.height + 2f), strokeWidth = 2f)
            }
        },
    )
}

/** Fila de un ajuste: nombre y valor arriba, deslizador debajo. Doble toque en la fila = restablecer. */
@Composable
fun AdjustmentRow(adjustment: Adjustment, value: Float, onChange: (Float) -> Unit, onReset: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { onReset() }) },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(adjustment.label, style = MaterialTheme.typography.bodyMedium, color = Palette.TextPrimary, modifier = Modifier.weight(1f))
            Text(
                adjustment.format(value),
                style = MaterialTheme.typography.bodyMedium,
                color = if (value == 0f) Palette.TextSecondary else Palette.Accent,
            )
        }
        ElegantSlider(value, onChange, adjustment.min..adjustment.max, Modifier.fillMaxWidth())
    }
}
