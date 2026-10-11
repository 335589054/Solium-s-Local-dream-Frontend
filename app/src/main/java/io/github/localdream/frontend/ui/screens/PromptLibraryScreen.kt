package io.github.localdream.frontend.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A pending destructive action awaiting confirmation. */
private sealed interface Confirm {
    data class DeleteTags(val tags: List<PromptTag>) : Confirm
    data class DeleteCategory(val path: String, val count: Int) : Confirm
    data object ImportBundled : Confirm
    data object ClearAll : Confirm
}

/**
 * Decodes an imported file.
 *
 * UTF-8 is tried first, but a text file written on Chinese Windows is usually
 * GBK, which decodes into replacement characters — so seeing any of those means
 * the file is not UTF-8 and GBK is worth a try.
 */
private fun decodeText(bytes: ByteArray): String {
    val utf8 = String(bytes, Charsets.UTF_8)
    if ('\uFFFD' !in utf8) return utf8
    return runCatching { String(bytes, charset("GBK")) }.getOrDefault(utf8)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PromptLibraryScreen(studio: StudioViewModel) {
    val library by studio.library.collectAsStateWithLifecycle()
    val studioState by studio.state.collectAsStateWithLifecycle()
    val presets by studio.modelPresetList.collectAsStateWithLifecycle()
    val connection by studio.connectionState.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current

    // Browsing path; empty means the top level. Each tap drills one level down.
    var path by remember { mutableStateOf<List<String>>(emptyList()) }
    var selecting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<Set<PromptTag>>(emptySet()) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }

    var importText by remember { mutableStateOf("") }
    var templateCopied by remember { mutableStateOf(false) }
    var newCategory by remember { mutableStateOf("") }
    var newZh by remember { mutableStateOf("") }
    var newEn by remember { mutableStateOf("") }
    var comboName by remember { mutableStateOf("") }
    var presetEditing by remember { mutableStateOf<RemoteModelInfo?>(null) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Pasting a few thousand lines runs into clipboard and text-field limits, so
    // picking a .txt file is the practical way to load a large library.
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val text = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                            ?.let { decodeText(it) }
                    }.getOrNull()
                }
                if (text.isNullOrBlank()) {
                    studio.notify("没有读到文件内容")
                } else {
                    val added = studio.importTags(text)
                    studio.notify(
                        if (added > 0) "导入完成，新增 $added 条标签" else "没有新增标签，可能都已在库中",
                    )
                }
            }
        }
    }

    val currentPath = path.joinToString("/").ifEmpty { null }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
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
                    Button(onClick = {
                        clipboard.setText(AnnotatedString(TagTemplate.TEXT))
                        templateCopied = true
                    }) {
                        Text(if (templateCopied) "已复制模板" else "复制模板")
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
                    Text("2 · 导入标签", style = MaterialTheme.typography.titleSmall)
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(onClick = { confirm = Confirm.ImportBundled }) {
                            Text("用内置词库重置")
                        }
                        OutlinedButton(
                            onClick = {
                                filePicker.launch(arrayOf("text/*", "application/octet-stream"))
                            },
                        ) {
                            Text("从 txt 文件导入")
                        }
                    }
                    Text(
                        "「用内置词库重置」会清空当前标签、换成随包附带的词库（收藏不受影响）。两种导入都按「分类 | 中文 | 英文」每行一条解析，可直接粘贴或选 txt 文件。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = importText,
                        onValueChange = { importText = it },
                        label = { Text("每行：分类 | 中文 | 英文") },
                        minLines = 5,
                        maxLines = 12,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = {
                            val added = studio.importTags(importText)
                            importText = ""
                            studio.notify(
                                if (added > 0) "导入完成，新增 $added 条标签" else "没有新增标签，可能都已在库中",
                            )
                        },
                        enabled = importText.isNotBlank(),
                    ) {
                        Icon(Icons.Filled.Download, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("导入粘贴内容")
                    }
                    Text(
                        "标签按分类归位，同一分类内一律按英文字母排序。当前共 ${library.tags.size} 个标签，${library.combos.size} 条收藏",
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
                        label = { Text("分类（用 / 分级，如 人物/脸部/眼睛）") },
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

        if (library.tags.isNotEmpty()) {
            item {
                TagBrowserCard(
                    library = library,
                    path = path,
                    onPathChange = {
                        path = it
                        selecting = false
                        selected = emptySet()
                    },
                    selecting = selecting,
                    selected = selected,
                    onSelectingChange = { selecting = it },
                    onSelectedChange = { selected = it },
                    onTagClick = { tag ->
                        clipboard.setText(AnnotatedString(tag.en))
                        studio.notify("已复制：${tag.en}")
                    },
                    onDeleteRequest = { confirm = it },
                    onMoveCategory = { fullPath, direction ->
                        studio.moveCategory(fullPath, direction)
                    },
                )
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

    confirm?.let { action ->
        val title: String
        val body: String
        val confirmLabel: String
        when (action) {
            is Confirm.DeleteTags -> {
                title = "删除标签"
                body = "将删除选中的 ${action.tags.size} 个标签，无法撤销"
                confirmLabel = "删除"
            }
            is Confirm.DeleteCategory -> {
                title = "删除分类"
                body = "将删除「${action.path}」及其所有子分类下的 ${action.count} 个标签，无法撤销"
                confirmLabel = "删除"
            }
            Confirm.ImportBundled -> {
                title = "用内置词库重置"
                body = "当前的 ${library.tags.size} 个标签会被全部清空，换成随包附带的内置词库；" +
                    "已收藏的 ${library.combos.size} 条提示词不受影响。此操作无法撤销。"
                confirmLabel = "清空并导入"
            }
            Confirm.ClearAll -> {
                title = "清空词库"
                body = "将清空全部 ${library.tags.size} 个标签，分类顺序也会一并重置，无法撤销；" +
                    "已收藏的 ${library.combos.size} 条提示词不受影响"
                confirmLabel = "清空"
            }
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) },
            text = { Text(body) },
            confirmButton = {
                TextButton(onClick = {
                    when (action) {
                        is Confirm.DeleteTags -> studio.removeTags(action.tags)
                        is Confirm.DeleteCategory -> {
                            studio.removeCategory(action.path)
                            path = emptyList()
                        }
                        Confirm.ImportBundled -> {
                            val count = studio.importBundledTags()
                            studio.notify(
                                if (count > 0) "已导入内置词库，共 $count 条标签" else "内置词库导入失败",
                            )
                        }
                        Confirm.ClearAll -> {
                            studio.clearTagLibrary()
                            studio.notify("词库已清空")
                        }
                    }
                    selected = emptySet()
                    selecting = false
                    path = emptyList()
                    confirm = null
                }) {
                    Text(confirmLabel)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirm = null }) { Text("取消") }
            },
        )
    }
}

/**
 * Walks the category tree one level at a time.
 *
 * A branch shows its sub-branches as chips; tags that sit directly on a branch
 * which also has sub-branches appear below under a `未分类` heading, so nothing
 * gets hidden by the hierarchy.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagBrowserCard(
    library: io.github.localdream.frontend.data.PromptLibrary,
    path: List<String>,
    onPathChange: (List<String>) -> Unit,
    selecting: Boolean,
    selected: Set<PromptTag>,
    onSelectingChange: (Boolean) -> Unit,
    onSelectedChange: (Set<PromptTag>) -> Unit,
    onTagClick: (PromptTag) -> Unit,
    onDeleteRequest: (Confirm) -> Unit,
    onMoveCategory: (String, Int) -> Unit,
) {
    val currentPath = path.joinToString("/").ifEmpty { null }
    // Sub-categories in display order: the user's arrangement first, then
    // whatever is still alphabetical.
    val branches = remember(library.tags, currentPath, library.categoryOrder) {
        library.orderedSubCategoriesOf(currentPath)
    }
    val branchNames = remember(branches) { branches.map { it.substringAfterLast('/') } }
    val direct = remember(library.tags, currentPath) { library.directTagsOf(currentPath) }
    val branchCounts = remember(library.tags, branches) {
        branches.associateWith { library.tagsUnder(it).size }
    }
    // Everything reachable from here, used by the select-all action.
    val scopeTags = remember(library.tags, currentPath) {
        if (currentPath == null) library.tags else library.tagsUnder(currentPath)
    }
    val allSelected = scopeTags.isNotEmpty() && selected.containsAll(scopeTags)
    // Sorting is per level, one category per row: the simplest thing that works
    // and needs no drag maths. The tags inside a category are always listed
    // alphabetically, so they are not sortable.
    var sorting by remember { mutableStateOf(false) }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("标签库", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = {
                    sorting = !sorting
                    if (sorting) {
                        onSelectingChange(false)
                        onSelectedChange(emptySet())
                    }
                }) {
                    Text(if (sorting) "完成" else "排序分类")
                }
                TextButton(
                    onClick = { onDeleteRequest(Confirm.ClearAll) },
                    enabled = library.tags.isNotEmpty(),
                ) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = null,
                        modifier = Modifier.width(16.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text("清空词库")
                }
            }

            // Breadcrumb
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    "全部",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (path.isEmpty()) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.primary
                    },
                    modifier = Modifier.clickable { onPathChange(emptyList()) },
                )
                path.forEachIndexed { index, level ->
                    Text("›", style = MaterialTheme.typography.labelMedium)
                    val last = index == path.lastIndex
                    Text(
                        level,
                        style = MaterialTheme.typography.labelMedium,
                        color = if (last) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                        modifier = if (last) {
                            Modifier
                        } else {
                            Modifier.clickable { onPathChange(path.take(index + 1)) }
                        },
                    )
                }
                Spacer(Modifier.weight(1f))
                if (currentPath != null) {
                    TextButton(onClick = {
                        onDeleteRequest(
                            Confirm.DeleteCategory(currentPath, library.tagsUnder(currentPath).size),
                        )
                    }) {
                        Text(
                            "删除本分类",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }

            if (selecting) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("已选 ${selected.size}", style = MaterialTheme.typography.labelMedium)
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = {
                        onSelectedChange(if (allSelected) emptySet() else scopeTags.toSet())
                    }) {
                        Text(if (allSelected) "取消全选" else "全选")
                    }
                    TextButton(
                        onClick = { onDeleteRequest(Confirm.DeleteTags(selected.toList())) },
                        enabled = selected.isNotEmpty(),
                    ) {
                        Text("删除选中")
                    }
                    TextButton(onClick = {
                        onSelectingChange(false)
                        onSelectedChange(emptySet())
                    }) {
                        Text("退出")
                    }
                }
            } else if (sorting) {
                Text(
                    "用 ↑ ↓ 调整下面这些分类的先后顺序，点「完成」退出；分类里的标签固定按英文字母排",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            } else {
                Text(
                    "点击标签复制，长按进入多选",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            val shown = direct

            if (sorting) {
                if (branches.isEmpty()) {
                    Text(
                        "这一层没有子分类可以排序，点面包屑回到上一层试试",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    branches.forEachIndexed { index, full ->
                        Row(
                            Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    branchNames[index],
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                Text(
                                    "${branchCounts[full] ?: 0} 个标签",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(
                                onClick = { onMoveCategory(full, -1) },
                                enabled = index > 0,
                            ) {
                                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "上移")
                            }
                            IconButton(
                                onClick = { onMoveCategory(full, 1) },
                                enabled = index < branches.lastIndex,
                            ) {
                                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "下移")
                            }
                        }
                    }
                }
            } else {
                if (branches.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        branches.forEachIndexed { index, full ->
                            FilterChip(
                                selected = false,
                                onClick = { onPathChange(path + branchNames[index]) },
                                label = {
                                    Text("${branchNames[index]} · ${branchCounts[full] ?: 0}")
                                },
                            )
                        }
                    }
                }

                if (branches.isNotEmpty() && shown.isNotEmpty()) {
                    Text(
                        "未分类",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (shown.isNotEmpty()) {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        shown.forEach { tag ->
                            TagCard(
                                tag = tag,
                                selected = tag in selected,
                                onClick = {
                                    if (selecting) {
                                        onSelectedChange(
                                            if (tag in selected) selected - tag else selected + tag,
                                        )
                                    } else {
                                        onTagClick(tag)
                                    }
                                },
                                onLongClick = {
                                    onSelectingChange(true)
                                    onSelectedChange(selected + tag)
                                },
                            )
                        }
                    }
                } else if (branches.isEmpty()) {
                    Text(
                        "该分类下暂无标签",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/**
 * A tag shows both attributes — the Chinese gloss on top and the English tag
 * underneath, which is what actually gets inserted into the prompt.
 */
@Composable
private fun TagCard(
    tag: PromptTag,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (selected) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = if (selected) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
        modifier = Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick),
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
