package io.github.localdream.frontend.ui.screens

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.localdream.frontend.data.AppSettings
import io.github.localdream.frontend.data.ConnPhase
import io.github.localdream.frontend.data.DeviceStats
import io.github.localdream.frontend.remote.RemoteProtocol
import io.github.localdream.frontend.ui.StudioState
import io.github.localdream.frontend.ui.StudioViewModel
import io.github.localdream.frontend.ui.components.ImageActions
import io.github.localdream.frontend.ui.components.ImageViewerDialog
import io.github.localdream.frontend.ui.components.MonitorBar
import io.github.localdream.frontend.ui.components.PromptSection
import io.github.localdream.frontend.ui.components.PromptTarget
import io.github.localdream.frontend.ui.components.TagPicker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

val SCHEDULERS = listOf(
    "dpm" to "DPM++ 2M",
    "dpm_karras" to "DPM++ 2M Karras",
    "dpm_sde" to "DPM++ 2M SDE",
    "dpm_sde_karras" to "DPM++ 2M SDE Karras",
    "euler_a" to "Euler A",
    "euler_a_karras" to "Euler A Karras",
    "euler" to "Euler",
    "euler_karras" to "Euler Karras",
    "lcm" to "LCM",
)

fun schedulerLabel(id: String): String = SCHEDULERS.firstOrNull { it.first == id }?.second ?: id

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun StudioScreen(
    studio: StudioViewModel,
    settings: AppSettings,
    stats: DeviceStats,
    expanded: Boolean,
) {
    val state by studio.state.collectAsStateWithLifecycle()
    val connection by studio.connectionState.collectAsStateWithLifecycle()
    val library by studio.library.collectAsStateWithLifecycle()

    var target by remember { mutableStateOf(PromptTarget.POSITIVE) }
    var viewerBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                }.getOrNull()
            }
            if (bitmap != null) studio.setBaseImage(bitmap)
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (settings.monitorEnabled) {
            MonitorBar(
                stats = stats,
                isGenerating = state.isGenerating,
                isUpscaling = state.isUpscaling,
                progress = state.progress,
                currentStep = state.currentStep,
                totalSteps = state.totalSteps,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }

        if (expanded) {
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier
                        .width(420.dp)
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    ControlColumn(studio, state, settings, connection.phase, library, target, { target = it }, imagePicker)
                }
                Box(
                    Modifier
                        .width(1.dp)
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.outlineVariant),
                )
                PreviewPane(
                    state = state,
                    onClick = { (state.preview ?: state.result)?.let { viewerBitmap = it } },
                    modifier = Modifier.weight(1f).fillMaxSize(),
                )
            }
        } else {
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ControlColumn(studio, state, settings, connection.phase, library, target, { target = it }, imagePicker)
                // The result sits at the bottom of the page, after the controls.
                PreviewPane(
                    state = state,
                    onClick = { (state.preview ?: state.result)?.let { viewerBitmap = it } },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }

    viewerBitmap?.let { bitmap ->
        ImageViewerDialog(
            bitmap = bitmap,
            title = "生成结果",
            meta = buildString {
                append("${bitmap.width}×${bitmap.height}")
                state.lastSeed?.let { append(" · seed $it") }
            },
            prompt = state.positivePrompt,
            actions = ImageActions(
                onSaveToGallery = { studio.saveBitmapToGallery(bitmap) },
                onSendToImg2Img = {
                    studio.loadHistoryAsBaseImage(bitmap)
                    viewerBitmap = null
                },
                onSendToUpscale = {
                    studio.upscaleFromHistory(bitmap)
                    viewerBitmap = null
                },
            ),
            onDismiss = { viewerBitmap = null },
        )
    }
}

@Composable
private fun ControlColumn(
    studio: StudioViewModel,
    state: StudioState,
    settings: AppSettings,
    phase: ConnPhase,
    library: io.github.localdream.frontend.data.PromptLibrary,
    target: PromptTarget,
    onTargetChange: (PromptTarget) -> Unit,
    imagePicker: androidx.activity.result.ActivityResultLauncher<PickVisualMediaRequest>,
) {
    if (phase != ConnPhase.CONNECTED) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Text(
                "尚未连接到 local-dream 主机，请点右上角状态条配置连接",
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
        }
    }

    // ---- prompts ----
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PromptSection(
                label = "正面提示词",
                tokens = state.positiveTokens,
                placeholder = "点击编辑，或在下方提示词库中点选",
                onIncreaseWeight = studio::increasePositiveWeight,
                onDecreaseWeight = studio::decreasePositiveWeight,
                onEditText = studio::setPositiveText,
                onClear = studio::clearPositive,
                supporting = state.promptTokenCount?.let { count ->
                    if (count > state.tokenLimit) "Token $count / ${state.tokenLimit}（已超出）" else "Token $count / ${state.tokenLimit}"
                },
            )

            PromptSection(
                label = "负面提示词",
                tokens = state.negativeTokens,
                placeholder = "点击编辑",
                onIncreaseWeight = studio::increaseNegativeWeight,
                onDecreaseWeight = studio::decreaseNegativeWeight,
                onEditText = studio::setNegativeText,
                onClear = studio::clearNegative,
            )

            // ---- img2img ----
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = {
                    imagePicker.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                    )
                }) {
                    Icon(Icons.Filled.Upload, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("上传图片")
                }
                state.baseImage?.let { base ->
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "图生图 ${base.width}×${base.height}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedButton(onClick = studio::clearBaseImage) { Text("取消") }
                }
            }

            if (state.img2imgActive) {
                Column {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("重绘强度", style = MaterialTheme.typography.labelMedium)
                        Text(
                            "%.2f".format(state.denoiseStrength),
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Slider(
                        value = state.denoiseStrength,
                        onValueChange = studio::setDenoiseStrength,
                        valueRange = 0.1f..1f,
                    )
                }
            }
        }
    }

    // ---- tag picker ----
    Card {
        Column(Modifier.padding(14.dp)) {
            TagPicker(
                tags = library.tags,
                target = target,
                onTargetChange = onTargetChange,
                onPick = { tag ->
                    when (target) {
                        PromptTarget.POSITIVE -> studio.addPositiveTag(tag.en, tag.zh)
                        PromptTarget.NEGATIVE -> studio.addNegativeTag(tag.en, tag.zh)
                    }
                },
                // Mirrors whatever is already in the field being edited, so the
                // picker shows the current selection.
                selectedTags = when (target) {
                    PromptTarget.POSITIVE -> state.positiveTokens.map { it.en }.toSet()
                    PromptTarget.NEGATIVE -> state.negativeTokens.map { it.en }.toSet()
                },
                categoryOrder = library.categoryOrder,
            )
        }
    }

    // ---- parameters ----
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("参数", style = MaterialTheme.typography.labelLarge)

            val presets = studio.availableResolutions()
            if (presets.isNotEmpty()) {
                Text("分辨率（仅预设）", style = MaterialTheme.typography.labelMedium)
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    presets.forEach { (w, h) ->
                        FilterChip(
                            selected = state.width == w && state.height == h,
                            onClick = { studio.setResolution(w, h) },
                            label = { Text("$w×$h") },
                        )
                    }
                }
            }

            LabeledSlider(
                label = "步数",
                value = state.steps.toFloat(),
                range = 1f..100f,
                display = state.steps.toString(),
                onChange = { studio.setSteps(it.roundToInt()) },
            )
            LabeledSlider(
                label = "CFG",
                value = state.cfg,
                range = 1f..20f,
                display = String.format("%.1f", state.cfg),
                onChange = studio::setCfg,
            )

            SchedulerDropdown(state.scheduler, studio::setScheduler)

            OutlinedTextField(
                value = state.seed,
                onValueChange = studio::setSeed,
                label = { Text("随机种子（留空为随机）") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("生成后自动超分", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (studio.canUpscale()) "完成后自动送往主机放大器再返图" else "主机未安装放大器",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = settings.autoUpscale,
                    onCheckedChange = studio::setAutoUpscale,
                    enabled = studio.canUpscale(),
                )
            }
        }
    }

    GenerateButtons(studio, state, phase)
}

@Composable
private fun PreviewPane(state: StudioState, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .padding(16.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = state.preview ?: state.result
        Column(
            Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            if (bitmap != null) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .aspectRatio(bitmap.width.toFloat() / bitmap.height.toFloat())
                        .clickable(onClick = onClick),
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "生成结果",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            } else {
                Icon(
                    Icons.Filled.Image,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (state.img2imgActive) "已载入底图，点击生成开始重绘" else "生成结果会显示在这里",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }

            if (state.isUpscaling) {
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("正在超分…", style = MaterialTheme.typography.bodySmall)
                }
            }

            if (state.result != null && !state.isGenerating && !state.isUpscaling) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    state.lastSeed?.let { seed ->
                        Text(
                            "seed: $seed",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "点击图片查看大图",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun LabeledSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    display: String,
    onChange: (Float) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(display, style = MaterialTheme.typography.labelMedium)
        }
        Slider(value = value, onValueChange = onChange, valueRange = range)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SchedulerDropdown(current: String, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = schedulerLabel(current),
            onValueChange = {},
            readOnly = true,
            label = { Text("采样器") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            SCHEDULERS.forEach { (id, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onChange(id)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun GenerateButtons(studio: StudioViewModel, state: StudioState, phase: ConnPhase) {
    val issue = studio.readinessIssue()
    val busy = state.isGenerating || state.isUpscaling

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.isGenerating) {
            Button(onClick = studio::cancelGeneration, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Stop, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("取消生成")
            }
        } else {
            Button(
                onClick = studio::generate,
                enabled = issue == null && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(if (state.img2imgActive) "生成（图生图）" else "生成")
            }

            if (state.result != null && !state.isUpscaling) {
                OutlinedButton(
                    onClick = studio::upscaleResult,
                    enabled = studio.canUpscale(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Filled.Image, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("超分当前结果")
                }
            }

            if (issue != null && phase == ConnPhase.CONNECTED) {
                Text(
                    issue,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}