package io.github.localdream.frontend.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.localdream.frontend.data.ModelPreset
import io.github.localdream.frontend.data.PromptCombo
import io.github.localdream.frontend.data.PromptTag
import io.github.localdream.frontend.data.TagTemplate
import io.github.localdream.frontend.remote.RemoteModelInfo
import io.github.localdream.frontend.ui.StudioViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PromptLibraryScreen(studio: StudioViewModel) {
    val library by studio.library.collectAsStateWithLifecycle()
    val studioState by studio.state.collectAsStateWithLifecycle()
    val presets by studio.modelPresetList.collectAsStateWithLifecycle()
    val connection by studio.connectionState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    var selectedCategory by remember { mutableIntStateOf(0) }
    var importText by remember { mutableStateOf("") }
    var templateCopied by remember { mutableStateOf(false) }
    var newCategory by remember { mutableStateOf("自定义") }
    var newZh by remember { mutableStateOf("") }
    var newEn by remember { mutableStateOf("") }
    var comboName by remember { mutableStateOf("") }
    var presetEditing by remember { mutableStateOf<RemoteModelInfo?>(null) }

    val categories = remember(library.tags) { library.categories }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            ModelPresetCard(
                models = connection.catalog?.models.orEmpty(),
                presets = presets,
                onEdit = { presetEditing = it },
            )
        }
        item {
            Card {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("1 · 复制模板给 AI", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "把模板复制到任意 AI 对话软件，填入你想整理的词，让它按「分类 | 中文 | 英文」输出",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = {
                            clipboard.setText(AnnotatedString(TagTemplate.TEXT))
                            templateCopied = true
                        }) {
                            Text(if (templateCopied) "已复制模板" else "复制模板")
                        }
                    }
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHighest,
                        shape = MaterialTheme.shapes.small,
                    ) {
                        Text(
                            TagTemplate.TEXT,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }

        item {
            Card {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("2 · 粘贴 AI 结果并导入", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        label = { Text("每行：分类 | 中文 | 英文") },
                        minLines = 5,
                        maxLines = 12,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                studio.importTags(importText)
                                importText = ""
                            },
                            enabled = importText.isNotBlank(),
                        ) {
                            Icon(Icons.Filled.Download, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("导入")
                        }
                        OutlinedButton(
                            onClick = studio::clearTagLibrary,
                            enabled = library.tags.isNotEmpty(),
                        ) {
                            Text("清空标签")
                        }
                    }
                    Text(
                        "当前共 ${library.tags.size} 个标签，${library.combos.size} 条收藏",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        item {
            Card {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("手动添加单个标签", style = MaterialTheme.typography.titleSmall)
                    OutlinedTextField(
                        value = newCategory,
                        onValueChange = { newCategory = it },
                        label = { Text("分类") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            value = newZh,
                            onValueChange = { newZh = it },
                            label = { Text("中文") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        OutlinedTextField(
                            value = newEn,
                            onValueChange = { newEn = it },
                            label = { Text("英文") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                    }
                    Button(
                        onClick = {
                            studio.addTag(newCategory, newZh, newEn)
                            newZh = ""
                            newEn = ""
                        },
                        enabled = newEn.isNotBlank(),
                    ) {
                        Text("添加")
                    }
                }
            }
        }

        if (categories.isNotEmpty()) {
            item {
                androidx.compose.material3.PrimaryScrollableTabRow(
                    selectedTabIndex = selectedCategory.coerceIn(0, categories.lastIndex),
                    edgePadding = 0.dp,
                ) {
                    categories.forEachIndexed { index, category ->
                        Tab(
                            selected = index == selectedCategory,
                            onClick = { selectedCategory = index },
                            text = { Text(category) },
                        )
                    }
                }
            }
            item {
                val category = categories[selectedCategory.coerceIn(0, categories.lastIndex)]
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "点击标签卡片把英文标签追加到提示词",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        library.tagsIn(category).forEach { tag ->
                            TagCard(tag = tag, onClick = {
                                clipboard.setText(AnnotatedString(tag.en))
                                studio.notify("已复制：${tag.en}")
                            })
                        }
                    }
                }
            }
        }

        item {
            Card {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("收藏当前提示词", style = MaterialTheme.typography.titleSmall)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedTextField(
                            value = comboName,
                            onValueChange = { comboName = it },
                            label = { Text("名称") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        IconButton(
                            onClick = {
                                studio.saveCombo(comboName)
                                comboName = ""
                            },
                            enabled = studioState.positiveTokens.isNotEmpty(),
                        ) {
                            Icon(Icons.Filled.Save, contentDescription = "收藏")
                        }
                    }
                    Text(
                        "将把创作台当前的提示词与反向提示词一起保存",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        if (library.combos.isNotEmpty()) {
            item {
                Text("收藏的提示词", style = MaterialTheme.typography.titleSmall)
            }
            items(library.combos, key = { it.id }) { combo ->
                ComboCard(
                    combo = combo,
                    onApply = { studio.applyCombo(combo.positive, combo.negative) },
                    onDelete = { studio.removeCombo(combo.id) },
                )
            }
        }
    }

    presetEditing?.let { model ->
        ModelPresetDialog(
            model = model,
            initial = presets.firstOrNull { it.modelId == model.id },
            onSave = { positive, negative -> studio.saveModelPreset(model.id, positive, negative) },
            onClear = { studio.clearModelPreset(model.id) },
            onDismiss = { presetEditing = null },
        )
    }
}

/**
 * A tag shows both attributes — the Chinese gloss on top and the English tag
 * underneath, which is what actually gets inserted into the prompt.
 */
@Composable
private fun TagCard(tag: PromptTag, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            if (tag.zh.isNotEmpty()) {
                Text(tag.zh, style = MaterialTheme.typography.labelLarge)
            }
            Text(
                tag.en,
                style = MaterialTheme.typography.labelSmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

/**
 * Per-model preset prompts: whatever is configured here is written into the
 * prompt fields automatically as soon as that model is selected in the studio.
 */
@Composable
private fun ModelPresetCard(
    models: List<RemoteModelInfo>,
    presets: List<ModelPreset>,
    onEdit: (RemoteModelInfo) -> Unit,
) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("模型预设提示词", style = MaterialTheme.typography.titleSmall)
            Text(
                "为某个模型设定后，在创作页选中它就会自动填入这些提示词",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (models.isEmpty()) {
                Text(
                    "尚未获取到模型列表，请先连接主机",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            models.forEach { model ->
                val preset = presets.firstOrNull { it.modelId == model.id }
                Row(
                    Modifier.fillMaxWidth().clickable { onEdit(model) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(model.name, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            preset?.positive?.takeIf { it.isNotBlank() }
                                ?.let { if (it.length > 40) it.take(40) + "…" else it }
                                ?: "未设置",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (preset != null) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            maxLines = 1,
                        )
                    }
                    TextButton(onClick = { onEdit(model) }) { Text("编辑") }
                }
            }
        }
    }
}

@Composable
private fun ModelPresetDialog(
    model: RemoteModelInfo,
    initial: ModelPreset?,
    onSave: (String, String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var positive by remember(model.id) { mutableStateOf(initial?.positive.orEmpty()) }
    var negative by remember(model.id) { mutableStateOf(initial?.negative.orEmpty()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${model.name} 的预设提示词") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = positive,
                    onValueChange = { positive = it },
                    label = { Text("预设正面提示词") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = negative,
                    onValueChange = { negative = it },
                    label = { Text("预设负面提示词") },
                    minLines = 2,
                    maxLines = 6,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    "留空表示选择该模型时不改动对应字段",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(positive, negative)
                onDismiss()
            }) {
                Text("保存")
            }
        },
        dismissButton = {
            Row {
                if (initial != null) {
                    TextButton(onClick = {
                        onClear()
                        onDismiss()
                    }) {
                        Text("清除")
                    }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun ComboCard(combo: PromptCombo, onApply: () -> Unit, onDelete: () -> Unit) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    combo.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "删除")
                }
            }
            Text(combo.positive, style = MaterialTheme.typography.bodySmall)
            if (combo.negative.isNotBlank()) {
                Text(
                    "反向：${combo.negative}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(4.dp))
            Button(onClick = onApply) { Text("应用到创作台") }
        }
    }
}