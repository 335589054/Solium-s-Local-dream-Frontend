package io.github.localdream.frontend.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Done
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.localdream.frontend.data.HistoryItem
import io.github.localdream.frontend.data.HistoryRepository
import io.github.localdream.frontend.ui.StudioViewModel
import io.github.localdream.frontend.ui.components.ImageActions
import io.github.localdream.frontend.ui.components.ImageViewerDialog
import kotlinx.coroutines.launch

/**
 * Generated images kept on device. A single tap opens the shared viewer; a long
 * press switches the grid into multi-select for bulk saving or deletion.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    studio: StudioViewModel,
    history: HistoryRepository,
    onOpenStudio: () -> Unit,
) {
    val items by history.items.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var selectionMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var viewing by remember { mutableStateOf<HistoryItem?>(null) }

    // Drop ids that no longer exist (e.g. after a bulk delete).
    LaunchedEffect(items) {
        if (selected.isNotEmpty()) {
            val alive = items.map { it.id }.toSet()
            if (selected.any { it !in alive }) selected = selected intersect alive
        }
        if (items.isEmpty()) selectionMode = false
    }

    Column(Modifier.fillMaxSize()) {
        if (selectionMode) {
            SelectionBar(
                count = selected.size,
                total = items.size,
                onSelectAll = { selected = items.map { it.id }.toSet() },
                onClearAll = { selected = emptySet() },
                onSave = {
                    scope.launch {
                        val targets = items.filter { it.id in selected }
                        val ok = targets.count { history.saveToGallery(it) }
                        studio.notify("已保存 $ok / ${targets.size} 张到图库")
                        selectionMode = false
                        selected = emptySet()
                    }
                },
                onDelete = {
                    items.filter { it.id in selected }.forEach(history::delete)
                    studio.notify("已删除 ${selected.size} 张")
                    selected = emptySet()
                    selectionMode = false
                },
                onExit = {
                    selectionMode = false
                    selected = emptySet()
                },
            )
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("历史", style = MaterialTheme.typography.titleMedium)
                Text(
                    "  共 ${items.size} 张",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                IconButton(onClick = history::clear, enabled = items.isNotEmpty()) {
                    Icon(Icons.Filled.DeleteSweep, contentDescription = "清空历史")
                }
            }
        }

        if (items.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    Icons.Filled.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "还没有生成记录",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 148.dp),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    HistoryCard(
                        item = item,
                        history = history,
                        selectionMode = selectionMode,
                        isSelected = item.id in selected,
                        onClick = {
                            if (selectionMode) {
                                selected = if (item.id in selected) {
                                    selected - item.id
                                } else {
                                    selected + item.id
                                }
                            } else {
                                viewing = item
                            }
                        },
                        onLongClick = {
                            selectionMode = true
                            selected = selected + item.id
                        },
                    )
                }
            }
        }
    }

    viewing?.let { item ->
        HistoryViewer(
            item = item,
            history = history,
            studio = studio,
            onDismiss = { viewing = null },
            onOpenStudio = {
                viewing = null
                onOpenStudio()
            },
        )
    }
}

@Composable
private fun SelectionBar(
    count: Int,
    total: Int,
    onSelectAll: () -> Unit,
    onClearAll: () -> Unit,
    onSave: () -> Unit,
    onDelete: () -> Unit,
    onExit: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onExit) {
                    Icon(Icons.Filled.Done, contentDescription = "退出多选")
                }
                Text("已选 $count / $total", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = if (count == total) onClearAll else onSelectAll) {
                    Text(if (count == total) "取消全选" else "全选")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onSave, enabled = count > 0) {
                    Icon(Icons.Filled.Save, contentDescription = null)
                    Spacer(Modifier.padding(2.dp))
                    Text("保存到图库")
                }
                TextButton(onClick = onDelete, enabled = count > 0) {
                    Icon(Icons.Filled.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error)
                    Spacer(Modifier.padding(2.dp))
                    Text("删除", color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryCard(
    item: HistoryItem,
    history: HistoryRepository,
    selectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val thumbnail by produceState<Bitmap?>(initialValue = null, item.id) {
        value = history.loadThumbnail(item)
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column {
            Box(
                Modifier.fillMaxWidth().aspectRatio(1f),
                contentAlignment = Alignment.Center,
            ) {
                thumbnail?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                if (isSelected) {
                    Box(
                        Modifier.fillMaxSize().padding(6.dp),
                        contentAlignment = Alignment.TopEnd,
                    ) {
                        Icon(
                            Icons.Filled.CheckCircle,
                            contentDescription = "已选中",
                            tint = Color(0xFF2E7D32),
                        )
                    }
                }
            }
            Column(Modifier.padding(8.dp)) {
                Text(
                    item.kind.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    "${item.displayTime()} · ${item.width}×${item.height}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** Full-screen view of one entry, using the same viewer as the studio result. */
@Composable
private fun HistoryViewer(
    item: HistoryItem,
    history: HistoryRepository,
    studio: StudioViewModel,
    onDismiss: () -> Unit,
    onOpenStudio: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var preview by remember { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(item.id) {
        preview = history.loadFull(item)
    }

    val bitmap = preview ?: return

    ImageViewerDialog(
        bitmap = bitmap,
        title = "${item.kind.label} · ${item.displayTime()}",
        meta = buildString {
            append("${item.width}×${item.height}")
            item.seed?.let { append(" · seed $it") }
            if (item.modelId.isNotEmpty()) append(" · ${item.modelId}")
        },
        prompt = item.prompt.ifBlank { null },
        actions = ImageActions(
            onSaveToGallery = {
                scope.launch {
                    studio.notify(
                        if (history.saveToGallery(item)) "已保存至图库" else "保存失败（需要 Android 10 及以上）",
                    )
                }
            },
            onSendToImg2Img = {
                studio.loadHistoryAsBaseImage(bitmap)
                onOpenStudio()
            },
            onSendToUpscale = {
                studio.upscaleFromHistory(bitmap)
                onOpenStudio()
            },
            onApplyPrompt = {
                studio.applyCombo(item.prompt, item.negativePrompt)
                studio.notify("已套用该图的提示词")
                onOpenStudio()
            },
        ),
        onDismiss = onDismiss,
    )
}