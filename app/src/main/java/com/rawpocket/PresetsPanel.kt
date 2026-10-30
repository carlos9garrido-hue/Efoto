package com.rawpocket

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Lo que el panel de presets necesita del ViewModel. */
class PresetActions(
    val onApply: (Preset) -> Unit,
    val onSave: (String) -> Unit,
    val onDelete: (Preset) -> Unit,
    val onCopy: () -> Unit,
    val onPaste: () -> Unit,
)

/** Presets de fábrica y propios, guardar el estado actual, y copiar/pegar ajustes entre fotos. */
@Composable
fun PresetsPanel(
    presets: List<Preset>,
    canPaste: Boolean,
    hasEdits: Boolean,
    actions: PresetActions,
) {
    var naming by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<Preset?>(null) }

    Column(Modifier.fillMaxWidth().padding(top = 6.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoftButton("Guardar actual", { naming = true }, enabled = hasEdits, accent = true)
            SoftButton("Copiar ajustes", actions.onCopy, enabled = hasEdits)
            SoftButton("Pegar", actions.onPaste, enabled = canPaste)
        }
        val mine = presets.filter { !it.builtIn }
        val factory = presets.filter { it.builtIn }
        if (mine.isNotEmpty()) {
            Overline("Mis presets")
            PresetRow(mine, onTap = actions.onApply, onLongPress = { confirmDelete = it })
            Text("Mantén pulsado un preset propio para borrarlo.", fontSize = 11.sp, color = Palette.TextSecondary)
        }
        Overline("Looks")
        PresetRow(factory, onTap = actions.onApply, onLongPress = null)
        Spacer(Modifier.height(6.dp))
    }

    if (naming) NameDialog(onDismiss = { naming = false }, onConfirm = { actions.onSave(it); naming = false })
    confirmDelete?.let { p ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            containerColor = Palette.SurfaceHigh,
            title = { Text("¿Borrar “${p.name}”?") },
            confirmButton = { TextButton({ actions.onDelete(p); confirmDelete = null }) { Text("Borrar", color = Palette.Accent) } },
            dismissButton = { TextButton({ confirmDelete = null }) { Text("Cancelar") } },
        )
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun PresetRow(list: List<Preset>, onTap: (Preset) -> Unit, onLongPress: ((Preset) -> Unit)?) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        list.forEach { p ->
            Column(
                Modifier
                    .width(104.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Palette.SurfaceHigh)
                    .border(1.dp, Palette.Outline, RoundedCornerShape(14.dp))
                    .then(
                        if (onLongPress != null) Modifier.androidxCombined(onTap = { onTap(p) }, onLong = { onLongPress(p) })
                        else Modifier.clickable { onTap(p) }
                    )
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(Palette.Accent))
                Text(p.name, style = MaterialTheme.typography.bodyMedium, maxLines = 2)
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.androidxCombined(onTap: () -> Unit, onLong: () -> Unit): Modifier =
    this.then(Modifier.combinedClickable(onClick = onTap, onLongClick = onLong))

@Composable
private fun NameDialog(onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Palette.SurfaceHigh,
        title = { Text("Nuevo preset") },
        text = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Palette.Background)
                    .border(1.dp, Palette.Outline, RoundedCornerShape(10.dp))
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                if (text.isEmpty()) Text("Nombre", color = Palette.TextSecondary)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it.take(32) },
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = Palette.TextPrimary),
                    cursorBrush = SolidColor(Palette.Accent),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton({ onConfirm(text) }) { Text("Guardar", color = Palette.Accent) } },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar") } },
    )
}
