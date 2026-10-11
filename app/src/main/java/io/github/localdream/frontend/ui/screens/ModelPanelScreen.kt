package io.github.localdream.frontend.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.localdream.frontend.data.ConnPhase
import io.github.localdream.frontend.remote.RemoteModelInfo
import io.github.localdream.frontend.remote.RemoteProtocol
import io.github.localdream.frontend.ui.StatusDot
import io.github.localdream.frontend.ui.StudioViewModel

@Composable
fun ModelPanelScreen(studio: StudioViewModel) {
    val connection by studio.connectionState.collectAsStateWithLifecycle()
    val state by studio.state.collectAsStateWithLifecycle()
    val catalog = connection.catalog

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            StatusHeader(studio)
        }

        if (catalog == null) {
            item {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text("尚无模型列表", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "连接成功后会自动拉取主机上的模型，也可以手动刷新",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.padding(top = 8.dp))
                        Button(onClick = studio::refreshCatalogNow, enabled = connection.phase == ConnPhase.CONNECTED) {
                            Text("刷新模型列表")
                        }
                    }
                }
            }
        } else {
            items(catalog.models, key = { it.id }) { model ->
                ModelCard(
                    model = model,
                    selected = state.selectedModelId == model.id,
                    running = connection.status?.servingModelId == model.id &&
                        connection.status?.state == RemoteProtocol.STATE_RUNNING,
                    onSelect = { studio.selectModel(model) },
                )
            }
        }

        if (catalog != null && catalog.upscalers.isNotEmpty()) {
            item {
                Text(
                    "放大器（${catalog.upscalers.size} 个）",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(catalog.upscalers, key = { it.id }) { upscaler ->
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text(upscaler.id, style = MaterialTheme.typography.titleSmall)
                        Text(
                            upscaler.path,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusHeader(studio: StudioViewModel) {
    val connection by studio.connectionState.collectAsStateWithLifecycle()
    val state by studio.state.collectAsStateWithLifecycle()
    val status = connection.status

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Column(
            Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (dotColor, text) = when {
                    connection.phase != ConnPhase.CONNECTED ->
                        MaterialTheme.colorScheme.outline to "未连接主机"

                    connection.activatingModelId != null ->
                        MaterialTheme.colorScheme.tertiary to "正在加载 ${connection.activatingModelId}"

                    status?.state == RemoteProtocol.STATE_RUNNING ->
                        MaterialTheme.colorScheme.primary to "运行中：${status.servingModelId}"

                    status?.state == RemoteProtocol.STATE_ERROR ->
                        MaterialTheme.colorScheme.error to "后端异常：${status.message ?: ""}"

                    else -> MaterialTheme.colorScheme.outline to "后端空闲（未加载模型）"
                }
                StatusDot(dotColor)
                Spacer(Modifier.width(8.dp))
                Text(text, style = MaterialTheme.typography.titleSmall)
            }

            status?.let {
                if (it.width != null && it.height != null) {
                    Text(
                        "当前分辨率：${it.width}×${it.height}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (connection.activatingModelId != null) {
                CircularProgressIndicator(Modifier.width(24.dp))
                Text(
                    "模型加载在手机上可能需要数十秒，请稍候",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = studio::startSelectedModel,
                    enabled = connection.phase == ConnPhase.CONNECTED && connection.activatingModelId == null,
                ) {
                    Text("启动所选模型")
                }
                OutlinedButton(
                    onClick = studio::stopModel,
                    enabled = status?.servingModelId != null,
                ) {
                    Text("关闭模型")
                }
                IconButton(onClick = studio::refreshCatalogNow) {
                    Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                }
            }
            Text(
                "手机内存有限，不需要生成时请及时关闭模型以释放内存",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (state.selectedModelId == null) {
                Text(
                    "未选择模型",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun ModelCard(
    model: RemoteModelInfo,
    selected: Boolean,
    running: Boolean,
    onSelect: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceContainerLow
            },
        ),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = selected, onClick = onSelect)
                Spacer(Modifier.width(4.dp))
                Text(
                    model.name,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                if (running) {
                    Spacer(Modifier.width(8.dp))
                    StatusDot(MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(4.dp))
                    Text("运行中", style = MaterialTheme.typography.labelSmall)
                }
            }
            if (model.description.isNotEmpty()) {
                Text(
                    model.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (model.isSdxl) Chip("SDXL")
                if (model.isAnima) Chip("Anima")
                if (model.runOnCpu) Chip("CPU")
                if (model.isCustom) Chip("自定义")
                Chip("${model.generationSize}px")
            }
            HorizontalDivider()
            Text(
                "默认：steps=${model.defaults.steps.toInt()} cfg=${model.defaults.cfg} " +
                    "采样器=${schedulerLabel(model.defaults.scheduler)}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (model.resolutions.isNotEmpty()) {
                Text(
                    "支持分辨率：" + model.resolutions.joinToString("、") { "${it.first}×${it.second}" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Chip(text: String) {
    AssistChip(onClick = {}, label = { Text(text) })
}