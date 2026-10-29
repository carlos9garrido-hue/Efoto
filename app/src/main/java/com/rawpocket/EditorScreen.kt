package com.rawpocket

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable
fun EditorScreen(
    state: EditorViewModel.UiState,
    onPick: () -> Unit,
    onParam: (Adjustment, Float) -> Unit,
    onResetParam: (Adjustment) -> Unit,
    onResetAll: () -> Unit,
    crop: CropActions,
    onHoldOriginal: (Boolean) -> Unit,
    onExport: () -> Unit,
    onMessageShown: () -> Unit,
) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(state.message) {
        val m = state.message ?: return@LaunchedEffect
        snackbar.showSnackbar(m)
        onMessageShown()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Palette.Background)
    ) {
        when (state.status) {
            EditorViewModel.Status.EMPTY -> EmptyScreen(onPick)
            EditorViewModel.Status.LOADING -> LoadingScreen()
            EditorViewModel.Status.ERROR -> ErrorScreen(state.error.orEmpty(), onPick)
            EditorViewModel.Status.READY -> ReadyScreen(
                state, onPick, onParam, onResetParam, onResetAll, crop, onHoldOriginal, onExport,
            )
        }
        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .padding(bottom = 16.dp),
        )
    }
}

// ------------------------------------------------------------------ pantallas de estado

@Composable
private fun EmptyScreen(onPick: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("RawPocket", style = MaterialTheme.typography.titleMedium.copy(letterSpacing = 4.sp))
        Spacer(Modifier.height(12.dp))
        Text(
            "Revela tus fotos RAW en el bolsillo.\nCR2, CR3, NEF, ARW, DNG, RAF, ORF, RW2 y más.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        Button(onClick = onPick, shape = RoundedCornerShape(12.dp)) { Text("Abrir foto RAW") }
    }
}

@Composable
private fun LoadingScreen() {
    Column(
        Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
        Spacer(Modifier.height(16.dp))
        Text("Revelando…", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ErrorScreen(message: String, onPick: () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(message, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        OutlinedButton(onClick = onPick, shape = RoundedCornerShape(12.dp)) { Text("Elegir otra foto") }
    }
}

// ------------------------------------------------------------------ editor

@Composable
private fun ReadyScreen(
    state: EditorViewModel.UiState,
    onPick: () -> Unit,
    onParam: (Adjustment, Float) -> Unit,
    onResetParam: (Adjustment) -> Unit,
    onResetAll: () -> Unit,
    crop: CropActions,
    onHoldOriginal: (Boolean) -> Unit,
    onExport: () -> Unit,
) {
    var group by rememberSaveable { mutableStateOf(Group.LIGHT) }
    var band by rememberSaveable { mutableStateOf(0) }  // banda de color seleccionada en HSL

    Column(Modifier.fillMaxSize()) {
        TopBar(state, onPick, onExport)

        // Foto: mantener pulsada para ver el original.
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            // Con la foto original (pulsación larga) se usan ajustes en cero.
            val shown = if (state.showOriginal) EditParams() else state.params
            val gpuParams = remember(shown) { shown.toFloatArray() }
            GpuPreview(photo = state.photo, params = gpuParams, modifier = Modifier.fillMaxSize())
            // Capa transparente que recibe el gesto: la SurfaceView de GL no debe tragarse los toques.
            Box(Modifier.fillMaxSize().holdToCompare(onHoldOriginal))
            if (state.showOriginal) {
                Text(
                    "ORIGINAL",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextPrimary,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 8.dp)
                        .clip(RoundedCornerShape(50))
                        .background(Palette.Background.copy(alpha = 0.7f))
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }
        }

        // Panel de controles.
        Column(
            Modifier
                .fillMaxWidth()
                .background(Palette.Surface)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Las pestañas se desplazan si no caben (pantallas estrechas).
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                    Group.entries.forEach { g ->
                        val selected = g == group
                        TextButton(onClick = { group = g }) {
                            Text(
                                g.label,
                                color = if (selected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                TextButton(onClick = onResetAll, enabled = !state.params.isDefault) { Text("Restablecer") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            Column(
                Modifier
                    .height(260.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                when (group) {
                    Group.CURVE -> CurvePreview(state.params)
                    Group.HSL -> BandPicker(band, state.params) { band = it }
                    Group.CROP -> CropPanel(state.params, crop)
                    else -> Unit
                }
                val bandFilter = if (group == Group.HSL) Band.entries[band] else null
                Adjustment.entries.filter { it.group == group && it.band == bandFilter }.forEach { adj ->
                    AdjustmentRow(
                        adjustment = adj,
                        value = adj.get(state.params),
                        onChange = { onParam(adj, it) },
                        onReset = { onResetParam(adj) },
                    )
                }
            }
        }
    }
}

@Composable
private fun TopBar(state: EditorViewModel.UiState, onPick: () -> Unit, onExport: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 8.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onPick) { Text("Abrir") }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                state.info?.camera?.ifBlank { state.fileName } ?: state.fileName,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val line = listOfNotNull(
                state.info?.exposureLine?.takeIf { it.isNotBlank() },
                state.info?.sizeLine?.takeIf { it.isNotBlank() },
            ).joinToString("  ·  ")
            if (line.isNotEmpty()) {
                Text(
                    line,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (state.exporting) {
            Box(Modifier.padding(horizontal = 12.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(
                    Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.primary,
                    strokeWidth = 2.dp,
                )
            }
        } else {
            Button(
                onClick = onExport,
                shape = RoundedCornerShape(10.dp),
                contentPadding = ButtonDefaults.ContentPadding,
            ) { Text("Exportar") }
        }
    }
}

@Composable
private fun AdjustmentRow(
    adjustment: Adjustment,
    value: Float,
    onChange: (Float) -> Unit,
    onReset: () -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(adjustment.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            // Tocar el valor lo restablece a 0.
            Text(
                adjustment.format(value),
                style = MaterialTheme.typography.bodyMedium,
                color = if (value == 0f) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable(onClick = onReset)
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = adjustment.min..adjustment.max,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = Palette.SurfaceHigh,
            ),
        )
    }
}

/** Dibuja la curva tonal resultante (misma fórmula que el motor) sobre una rejilla. */
@Composable
private fun CurvePreview(params: EditParams) {
    val offsets = params.curveOffsets()
    val accent = Palette.Accent
    val grid = Palette.Outline
    Canvas(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
            .height(110.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Palette.SurfaceHigh)
    ) {
        val w = size.width
        val h = size.height
        for (i in 1..3) {
            val f = i / 4f
            drawLine(grid, Offset(w * f, 0f), Offset(w * f, h), strokeWidth = 1f)
            drawLine(grid, Offset(0f, h * f), Offset(w, h * f), strokeWidth = 1f)
        }
        drawLine(grid, Offset(0f, h), Offset(w, 0f), strokeWidth = 1f)  // diagonal = sin cambios

        val path = Path()
        val steps = 64
        for (i in 0..steps) {
            val x = i / steps.toFloat()
            val px = x * w
            val py = h - curveValue(offsets, x) * h
            if (i == 0) path.moveTo(px, py) else path.lineTo(px, py)
        }
        drawPath(path, accent, style = Stroke(width = 3f))
        for (i in 0 until Adjustment.CURVE_POINTS) {
            val x = i / 4f
            drawCircle(accent, radius = 5f, center = Offset(w * x, h - curveValue(offsets, x) * h))
        }
    }
}

/** Selector de banda de color para HSL; un punto oscuro marca las bandas con ediciones. */
@Composable
private fun BandPicker(selected: Int, params: EditParams, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Band.entries.forEachIndexed { i, band ->
            val edited = Adjustment.bandRange(i).any { params.values[it] != 0f }
            Box(
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(band.argb))
                    .then(if (i == selected) Modifier.border(2.dp, Palette.TextPrimary, CircleShape) else Modifier)
                    .clickable { onSelect(i) },
                contentAlignment = Alignment.Center,
            ) {
                if (edited) Box(Modifier.size(6.dp).clip(CircleShape).background(Palette.Background))
            }
        }
    }
}

/** Acciones del panel Recorte (las implementa el ViewModel). */
class CropActions(
    val onAngle: (Float) -> Unit,
    val onRotate: (Int) -> Unit,
    val onAspect: (Int) -> Unit,
    val onReset: () -> Unit,
)

/** Girar 90°, enderezar con deslizador y elegir proporción; el recorte se ajusta solo. */
@Composable
private fun CropPanel(params: EditParams, actions: CropActions) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(onClick = { actions.onRotate(-1) }, shape = RoundedCornerShape(10.dp)) { Text("Girar izq.") }
            OutlinedButton(onClick = { actions.onRotate(1) }, shape = RoundedCornerShape(10.dp)) { Text("Girar der.") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = actions.onReset, enabled = params.hasGeometry) { Text("Quitar") }
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Enderezar", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            val angle = params.angle
            // Tocar el valor lo restablece a 0.
            Text(
                if (angle == 0f) "0°" else String.format(Locale.US, "%+.1f°", angle),
                style = MaterialTheme.typography.bodyMedium,
                color = if (angle == 0f) MaterialTheme.colorScheme.onSurfaceVariant
                else MaterialTheme.colorScheme.primary,
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { actions.onAngle(0f) }
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Slider(
            value = params.angle,
            onValueChange = actions.onAngle,
            valueRange = -EditParams.MAX_ANGLE..EditParams.MAX_ANGLE,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = Palette.SurfaceHigh,
            ),
        )
        Text("Proporción", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AspectPreset.entries.forEachIndexed { i, preset ->
                val selected = i == params.aspect
                Text(
                    preset.label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) MaterialTheme.colorScheme.primary else Palette.SurfaceHigh)
                        .clickable { actions.onAspect(i) }
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
    }
}

// ------------------------------------------------------------------ utilidades

/** Mientras el dedo está sobre la foto se notifica `true`; al soltarlo (o cancelarse el gesto), `false`. */
private fun Modifier.holdToCompare(onHold: (Boolean) -> Unit): Modifier = composed {
    val callback by rememberUpdatedState(onHold)
    pointerInput(Unit) {
        awaitEachGesture {
            awaitFirstDown(requireUnconsumed = false)
            callback(true)
            waitForUpOrCancellation()
            callback(false)
        }
    }
}
