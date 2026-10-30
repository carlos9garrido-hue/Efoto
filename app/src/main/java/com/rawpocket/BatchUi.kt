package com.rawpocket

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp

/** Opciones de exportación: calidad JPEG (1..100) y lado largo máximo en píxeles (0 = original). */
data class ExportOptions(val quality: Int = 95, val maxEdge: Int = 0) {
    companion object {
        val qualities = listOf("Máxima" to 95, "Alta" to 90, "Media" to 80, "Ligera" to 65)
        val sizes = listOf("Original" to 0, "4096 px" to 4096, "2048 px" to 2048, "1080 px" to 1080)
    }
}

/** Avance de una exportación por lotes. */
data class BatchState(
    val total: Int,
    val done: Int,
    val current: String = "",
    val failed: Int = 0,
    val finished: Boolean = false,
)

class BatchActions(
    val onExport: (List<RecentItem>, ExportOptions) -> Unit,
    val onCancel: () -> Unit,
    val onDismiss: () -> Unit,
)

/** Elegir calidad y tamaño antes de exportar (una foto o varias). */
@Composable
fun ExportDialog(title: String, onDismiss: () -> Unit, onConfirm: (ExportOptions) -> Unit) {
    var q by rememberSaveable { mutableStateOf(0) }
    var s by rememberSaveable { mutableStateOf(0) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.SurfaceHigh,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Overline("Calidad")
                Options(ExportOptions.qualities.map { it.first }, q) { q = it }
                Overline("Tamaño (lado largo)")
                Options(ExportOptions.sizes.map { it.first }, s) { s = it }
                Text(
                    "Se guarda en Pictures/Efoto como JPG.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Palette.TextSecondary,
                )
            }
        },
        confirmButton = {
            TextButton({ onConfirm(ExportOptions(ExportOptions.qualities[q].second, ExportOptions.sizes[s].second)) }) {
                Text("Exportar", color = Palette.Accent)
            }
        },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar") } },
    )
}

@Composable
private fun Options(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.chunked(2).forEachIndexed { row, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEachIndexed { col, label ->
                    val i = row * 2 + col
                    val on = i == selected
                    Text(
                        label,
                        color = if (on) androidx.compose.ui.graphics.Color(0xFF1B1405) else Palette.TextPrimary,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (on) Palette.Accent else Palette.Background)
                            .border(1.dp, if (on) Palette.Accent else Palette.Outline, RoundedCornerShape(50))
                            .clickable { onSelect(i) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }
}

/** Progreso de la exportación por lotes. No se puede cerrar hasta que termina; se puede cancelar. */
@Composable
fun BatchProgressDialog(state: BatchState, onCancel: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        containerColor = Palette.SurfaceHigh,
        title = { Text(if (state.finished) "Exportación terminada" else "Exportando…") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                LinearProgressIndicator(
                    progress = { if (state.total == 0) 0f else state.done.toFloat() / state.total },
                    modifier = Modifier.fillMaxWidth(),
                    color = Palette.Accent,
                    trackColor = Palette.Outline,
                )
                Text(
                    if (state.finished) {
                        val ok = state.total - state.failed
                        "$ok de ${state.total} guardadas en Pictures/Efoto" +
                            if (state.failed > 0) ". ${state.failed} no se pudieron exportar (archivo movido o sin acceso)." else "."
                    } else "${state.done + 1} de ${state.total}  ·  ${state.current}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Palette.TextSecondary,
                )
            }
        },
        confirmButton = {
            if (state.finished) TextButton(onDismiss) { Text("Listo", color = Palette.Accent) }
        },
        dismissButton = {
            if (!state.finished) TextButton(onCancel) { Text("Cancelar") }
        },
    )
}
