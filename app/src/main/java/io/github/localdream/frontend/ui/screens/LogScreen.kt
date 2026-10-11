package io.github.localdream.frontend.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.localdream.frontend.data.LogLevel
import io.github.localdream.frontend.data.LogRepository

@Composable
fun LogScreen(log: LogRepository) {
    val entries by log.entries.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    val listState = rememberLazyListState()
    var levelFilter by remember { mutableStateOf<LogLevel?>(null) }

    val filtered = remember(entries, levelFilter) {
        levelFilter?.let { level -> entries.filter { it.level == level } } ?: entries
    }

    // Keep the newest line in view as the log grows.
    LaunchedEffect(filtered.size) {
        if (filtered.isNotEmpty()) listState.animateScrollToItem(filtered.lastIndex)
    }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = levelFilter == null,
                onClick = { levelFilter = null },
                label = { Text("全部 ${entries.size}") },
            )
            LogLevel.entries.forEach { level ->
                FilterChip(
                    selected = levelFilter == level,
                    onClick = { levelFilter = level },
                    label = { Text(level.label) },
                )
            }
            IconButton(
                onClick = { clipboard.setText(AnnotatedString(log.exportText())) },
                enabled = entries.isNotEmpty(),
            ) {
                Icon(Icons.Filled.ContentCopy, contentDescription = "复制全部日志")
            }
            IconButton(
                onClick = { log.clear() },
                enabled = entries.isNotEmpty(),
            ) {
                Icon(Icons.Filled.DeleteSweep, contentDescription = "清空日志")
            }
        }

        if (filtered.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "暂无日志，操作记录会显示在这里",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(filtered, key = { it.id }) { entry ->
                    val color = when (entry.level) {
                        LogLevel.DEBUG -> MaterialTheme.colorScheme.onSurfaceVariant
                        LogLevel.INFO -> MaterialTheme.colorScheme.onSurface
                        LogLevel.WARN -> MaterialTheme.colorScheme.tertiary
                        LogLevel.ERROR -> MaterialTheme.colorScheme.error
                    }
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceContainerLow)
                            .padding(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                entry.formattedTime(),
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                "  ${entry.level.label}  [${entry.tag}]",
                                style = MaterialTheme.typography.labelSmall,
                                fontFamily = FontFamily.Monospace,
                                color = color,
                            )
                        }
                        Text(
                            entry.message,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}