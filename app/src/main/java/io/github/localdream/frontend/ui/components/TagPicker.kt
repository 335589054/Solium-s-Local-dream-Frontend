package io.github.localdream.frontend.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.localdream.frontend.data.PromptTag

/** Which field the tag picker currently feeds. */
enum class PromptTarget(val label: String) {
    POSITIVE("加到正面"),
    NEGATIVE("加到负面"),
}

/**
 * The studio-side tag browser: pick a category, then tap a tag to append it to
 * whichever prompt field the segmented control points at. Managing the library
 * itself (import / edit / delete) lives in settings.
 *
 * Tags already present in the targeted prompt field are highlighted, so the
 * picker doubles as an at-a-glance view of what has been chosen.
 */
@Composable
fun TagPicker(
    tags: List<PromptTag>,
    target: PromptTarget,
    onTargetChange: (PromptTarget) -> Unit,
    onPick: (PromptTag) -> Unit,
    modifier: Modifier = Modifier,
    selectedTags: Set<String> = emptySet(),
) {
    val topCategories = remember(tags) {
        tags.mapNotNull { it.levels.firstOrNull() }.distinct().sorted()
    }
    var topIndex by rememberSaveable { mutableIntStateOf(0) }
    // Full path of the sub-category being viewed; null means the whole top level.
    var subPath by rememberSaveable { mutableStateOf<String?>(null) }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("提示词库", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            if (tags.isNotEmpty()) {
                SingleChoiceSegmentedButtonRow {
                    PromptTarget.entries.forEachIndexed { index, entry ->
                        SegmentedButton(
                            selected = target == entry,
                            onClick = { onTargetChange(entry) },
                            shape = SegmentedButtonDefaults.itemShape(index, PromptTarget.entries.size),
                        ) {
                            Text(entry.label, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }
        }

        if (tags.isEmpty()) {
            Text(
                "标签库为空，可到「设置 → 提示词库」用 AI 模板导入",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            return@Column
        }

        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(topCategories) { category ->
                FilterChip(
                    selected = topCategories.getOrNull(topIndex) == category,
                    onClick = {
                        topIndex = topCategories.indexOf(category)
                        subPath = null
                    },
                    label = { Text(category) },
                )
            }
        }

        val currentTop = topCategories.getOrNull(topIndex.coerceIn(0, topCategories.lastIndex))
        val subCategories = remember(tags, currentTop) {
            tags.mapNotNull { tag ->
                val levels = tag.levels
                if (levels.size >= 2 && levels[0] == currentTop) {
                    levels.take(2).joinToString("/")
                } else {
                    null
                }
            }.distinct().sorted()
        }

        if (subCategories.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                item {
                    FilterChip(
                        selected = subPath == null,
                        onClick = { subPath = null },
                        label = { Text("全部") },
                    )
                }
                items(subCategories) { full ->
                    FilterChip(
                        selected = subPath == full,
                        onClick = { subPath = full },
                        label = { Text(full.substringAfter('/')) },
                    )
                }
            }
        }

        val visible = remember(tags, currentTop, subPath) {
            when {
                currentTop == null -> emptyList()
                subPath != null -> tags.filter { it.category == subPath }
                else -> tags.filter {
                    it.category == currentTop || it.category.startsWith("$currentTop/")
                }
            }
        }

        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 104.dp),
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp, max = 248.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            items(visible, key = { it.category + "/" + it.en }) { tag ->
                TagTile(
                    tag = tag,
                    selected = tag.en in selectedTags,
                    onClick = { onPick(tag) },
                )
            }
        }
    }
}

/**
 * English on the top half, translation on the bottom. [selected] marks a tag
 * that is already part of the targeted prompt field.
 */
@Composable
private fun TagTile(tag: PromptTag, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceContainerHigh
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSurface
        },
        modifier = Modifier.fillMaxWidth().height(52.dp),
    ) {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    tag.en,
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    tag.zh.ifEmpty { "—" },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "已在提示词中",
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .size(13.dp),
                )
            }
        }
    }
}