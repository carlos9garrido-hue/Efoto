package com.rawpocket

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun EditorScreen(
    state: EditorViewModel.UiState,
    onPick: () -> Unit,
    onParam: (Adjustment, Float) -> Unit,
    onResetParam: (Adjustment) -> Unit,
    onResetAll: () -> Unit,
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
                state, onPick, onParam, onResetParam, onResetAll, onHoldOriginal, onExport,
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
    onHoldOriginal: (Boolean) -> Unit,
    onExport: () -> Unit,
) {
    var group by rememberSaveable { mutableStateOf(Group.LIGHT) }

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
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onResetAll, enabled = !state.params.isDefault) { Text("Restablecer") }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
            Column(
                Modifier
                    .height(260.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Adjustment.entries.filter { it.group == group }.forEach { adj ->
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
