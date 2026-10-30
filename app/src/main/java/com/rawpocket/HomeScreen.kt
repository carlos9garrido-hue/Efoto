package com.rawpocket

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Primera pantalla: abrir una foto nueva o volver a una reciente. No abre nada por sí sola. */
@Composable
fun HomeScreen(
    recents: List<RecentItem>,
    thumbDir: File,
    onPick: () -> Unit,
    onOpenRecent: (RecentItem) -> Unit,
    onRemoveRecent: (RecentItem) -> Unit,
    onExportSelected: (List<RecentItem>, ExportOptions) -> Unit,
) {
    // Selección múltiple: se entra con una pulsación larga sobre una foto.
    var selected by remember { mutableStateOf(setOf<String>()) }
    var askExport by remember { mutableStateOf(false) }
    val selecting = selected.isNotEmpty()
    BackHandler(enabled = selecting) { selected = emptySet() }
    // Si una foto desaparece de recientes, deja de estar seleccionada.
    val valid = recents.map { it.uri }.toSet()
    if (selected.any { it !in valid }) selected = selected.filter { it in valid }.toSet()

    Box(Modifier.fillMaxSize()) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp),
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        contentPadding = PaddingValues(16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 20.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("efoto", style = MaterialTheme.typography.titleLarge)
                    Box(Modifier.padding(start = 6.dp, top = 6.dp).size(7.dp).clip(CircleShape).background(Palette.Accent))
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    "Revelado RAW, JPG y PNG en tu bolsillo",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(26.dp))
                // Tarjeta principal para abrir una foto.
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(18.dp))
                        .background(Palette.Surface)
                        .border(1.dp, Palette.Outline, RoundedCornerShape(18.dp))
                        .clickable(onClick = onPick)
                        .padding(horizontal = 18.dp, vertical = 18.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(44.dp).clip(CircleShape).background(Palette.Accent),
                        contentAlignment = Alignment.Center,
                    ) { IconGlyph(ToolIcon.PLUS, Color(0xFF1B1405), Modifier.size(22.dp)) }
                    Column(Modifier.padding(start = 14.dp)) {
                        Text("Abrir foto", style = MaterialTheme.typography.titleMedium)
                        Text("Elige un RAW, JPG o PNG", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        if (recents.isEmpty()) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "Aquí aparecerán las fotos que abras, para volver a ellas con un toque.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                )
            }
        } else {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                    "Recientes",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            items(recents, key = { it.uri }) { item ->
                RecentTile(
                    item, thumbDir,
                    selecting = selecting,
                    selected = item.uri in selected,
                    onClick = {
                        if (selecting) selected = if (item.uri in selected) selected - item.uri else selected + item.uri
                        else onOpenRecent(item)
                    },
                    onLongClick = { selected = selected + item.uri },
                    onRemove = { onRemoveRecent(item) },
                )
            }
        }
    }

    if (selecting) {
        Row(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(16.dp)
                .clip(RoundedCornerShape(50))
                .background(Palette.SurfaceHigh)
                .border(1.dp, Palette.Outline, RoundedCornerShape(50))
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            IconAction(ToolIcon.CLOSE, { selected = emptySet() })
            Text("${selected.size} seleccionadas", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 6.dp))
            SoftButton("Todas", { selected = recents.map { it.uri }.toSet() })
            SoftButton("Exportar", { askExport = true }, accent = true)
        }
    }
    if (askExport) {
        ExportDialog(
            title = "Exportar ${selected.size} fotos",
            onDismiss = { askExport = false },
            onConfirm = { opts ->
                askExport = false
                val items = recents.filter { it.uri in selected }
                selected = emptySet()
                onExportSelected(items, opts)
            },
        )
    }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun RecentTile(
    item: RecentItem,
    thumbDir: File,
    selecting: Boolean,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRemove: () -> Unit,
) {
    val bitmap by produceState<Bitmap?>(null, item.thumb) {
        value = item.thumb?.let { name ->
            withContext(Dispatchers.IO) { runCatching { BitmapFactory.decodeFile(File(thumbDir, name).path) }.getOrNull() }
        }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .clip(RoundedCornerShape(14.dp))
            .background(Palette.SurfaceHigh)
            .then(if (selected) Modifier.border(2.dp, Palette.Accent, RoundedCornerShape(14.dp)) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        val b = bitmap
        if (b != null) {
            Image(
                bitmap = b.asImageBitmap(),
                contentDescription = item.name,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        } else {
            Text(
                item.name.substringAfterLast('.', "").uppercase(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.Center),
            )
        }
        // Degradado inferior con el nombre.
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f))))
                .padding(start = 8.dp, end = 8.dp, top = 18.dp, bottom = 6.dp),
        ) {
            Text(
                item.name.substringBeforeLast('.'),
                style = MaterialTheme.typography.bodySmall,
                color = Palette.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (selecting) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(if (selected) Palette.Accent else Color.Black.copy(alpha = 0.45f))
                    .border(1.5.dp, if (selected) Palette.Accent else Palette.TextPrimary, CircleShape),
                contentAlignment = Alignment.Center,
            ) { if (selected) IconGlyph(ToolIcon.CHECK, Color(0xFF1B1405), Modifier.size(14.dp)) }
        } else {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable(onClick = onRemove),
                contentAlignment = Alignment.Center,
            ) { IconGlyph(ToolIcon.CLOSE, Palette.TextPrimary, Modifier.size(12.dp)) }
        }
    }
}
