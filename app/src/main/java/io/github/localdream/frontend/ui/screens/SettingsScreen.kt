package io.github.localdream.frontend.ui.screens

import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.localdream.frontend.AppContainer
import io.github.localdream.frontend.data.AppSettings
import io.github.localdream.frontend.data.DeviceStats
import io.github.localdream.frontend.data.ShellAccess
import io.github.localdream.frontend.ui.StudioViewModel
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

private const val SHIZUKU_REQUEST_CODE = 4213

private enum class SettingsPage(val title: String) {
    ROOT("设置"),
    CONNECTION("连接"),
    WEBUI("WebUI 服务器"),
    APPEARANCE("外观"),
    GENERATION("生成"),
    LIBRARY("提示词库"),
    MONITOR("设备监控"),
    LOGS("日志"),
}

@Composable
fun SettingsScreen(
    container: AppContainer,
    settings: AppSettings,
    studio: StudioViewModel,
) {
    var page by rememberSaveable { mutableStateOf(SettingsPage.ROOT) }

    BackHandler(enabled = page != SettingsPage.ROOT) {
        page = SettingsPage.ROOT
    }

    when (page) {
        SettingsPage.ROOT -> SettingsHome(
            settings = settings,
            tagCount = studio.library.value.tags.size,
            logCount = container.log.entries.value.size,
            onOpen = { page = it },
        )

        SettingsPage.CONNECTION -> SubPage(SettingsPage.CONNECTION, onBack = { page = SettingsPage.ROOT }) {
            ConnectionSettings(container, settings)
        }

        SettingsPage.WEBUI -> SubPage(SettingsPage.WEBUI, onBack = { page = SettingsPage.ROOT }) {
            WebUiSettings(container, settings)
        }

        SettingsPage.APPEARANCE -> SubPage(SettingsPage.APPEARANCE, onBack = { page = SettingsPage.ROOT }) {
            AppearanceSettings(container, settings)
        }

        SettingsPage.GENERATION -> SubPage(SettingsPage.GENERATION, onBack = { page = SettingsPage.ROOT }) {
            GenerationSettings(container, settings)
        }

        SettingsPage.LIBRARY -> SubPage(SettingsPage.LIBRARY, onBack = { page = SettingsPage.ROOT }) {
            PromptLibraryScreen(studio)
        }

        SettingsPage.MONITOR -> SubPage(SettingsPage.MONITOR, onBack = { page = SettingsPage.ROOT }) {
            DeviceMonitorSettings(container, settings)
        }

        SettingsPage.LOGS -> SubPage(SettingsPage.LOGS, onBack = { page = SettingsPage.ROOT }) {
            LogScreen(container.log)
        }
    }
}

@Composable
private fun SubPage(
    page: SettingsPage,
    onBack: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text(page.title, style = MaterialTheme.typography.titleMedium)
        }
        HorizontalDivider()
        // Weight keeps the sub-page inside the remaining height so its own
        // scrolling lists are not overlapped by the header.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            content()
        }
    }
}

@Composable
private fun SettingsHome(
    settings: AppSettings,
    tagCount: Int,
    logCount: Int,
    onOpen: (SettingsPage) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("连接到主机", style = MaterialTheme.typography.titleSmall)
                Text(
                    "${settings.host}:${settings.controlPort}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    if (settings.autoConnect) "启动时自动连接" else "手动连接",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        SettingsEntry("连接", "主机地址与控制端口") { onOpen(SettingsPage.CONNECTION) }
        SettingsEntry(
            "WebUI 服务器",
            if (settings.webUiEnabled) "已开启 · 端口 ${settings.webUiPort}" else "已关闭",
        ) {
            onOpen(SettingsPage.WEBUI)
        }
        SettingsEntry("外观", "主题、取色与深色模式") { onOpen(SettingsPage.APPEARANCE) }
        SettingsEntry("生成", "扩散过程预览与刷新间隔") { onOpen(SettingsPage.GENERATION) }
        SettingsEntry("提示词库", if (tagCount > 0) "$tagCount 个标签" else "尚未导入标签") {
            onOpen(SettingsPage.LIBRARY)
        }
        SettingsEntry(
            "设备监控",
            if (settings.monitorEnabled) "已开启 · Shizuku 与监控项" else "已关闭",
        ) {
            onOpen(SettingsPage.MONITOR)
        }
        SettingsEntry("日志", if (logCount > 0) "$logCount 条记录" else "暂无记录") {
            onOpen(SettingsPage.LOGS)
        }

        Text(
            "LocalDream 控制台 · 仅作为受控端前端，不包含本地推理后端",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SettingsEntry(title: String, subtitle: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ---------------- connection ----------------

@Composable
private fun ConnectionSettings(container: AppContainer, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    SectionColumn {
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("主机", style = MaterialTheme.typography.titleSmall)
                Text(
                    "当前：${settings.host}:${settings.controlPort}",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    "点击顶部状态条可打开连接页，修改主机地址与端口",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SwitchRow(
                    label = "启动时自动连接",
                    checked = settings.autoConnect,
                    onCheckedChange = { scope.launch { container.settings.setAutoConnect(it) } },
                )
            }
        }
    }
}

// ---------------- WebUI bridge ----------------

@Composable
private fun WebUiSettings(container: AppContainer, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    val serverState by container.webUiServer.state.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var portText by remember(settings.webUiPort) { mutableStateOf(settings.webUiPort.toString()) }

    SectionColumn {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("把本机变成 WebUI 服务器", style = MaterialTheme.typography.titleSmall)
                Text(
                    "开启后，局域网内的浏览器或任何支持 AUTOMATIC1111 API 的客户端，都能用这台手机的 local-dream 后端出图",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                SwitchRow("启用 WebUI 服务器", settings.webUiEnabled) {
                    scope.launch { container.settings.setWebUiEnabled(it) }
                }
                HorizontalDivider()
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = portText,
                        onValueChange = { input -> portText = input.filter { it.isDigit() }.take(5) },
                        label = { Text("监听端口") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val port = portText.toIntOrNull() ?: 7860
                            scope.launch { container.settings.setWebUiPort(port) }
                        },
                        enabled = portText.toIntOrNull() != settings.webUiPort,
                    ) {
                        Text("应用")
                    }
                }
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("访问地址", style = MaterialTheme.typography.titleSmall)
                val running = serverState.running
                Text(
                    if (running) "运行中 · 端口 ${serverState.port}" else "未运行",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (running) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                serverState.error?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                if (serverState.addresses.isEmpty()) {
                    Text(
                        "未检测到局域网地址，请确认已连接 WLAN",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    serverState.addresses.forEach { address ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                address,
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = {
                                clipboard.setText(AnnotatedString(address))
                            }) {
                                Text("复制")
                            }
                        }
                    }
                }
                if (running) {
                    Button(
                        onClick = {
                            val first = serverState.addresses.firstOrNull()
                            if (first != null) {
                                clipboard.setText(AnnotatedString(first))
                            }
                        },
                        enabled = serverState.addresses.isNotEmpty(),
                    ) {
                        Text("复制地址")
                    }
                }
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("已实现的 WebUI 接口", style = MaterialTheme.typography.titleSmall)
                listOf(
                    "GET  /                     内置网页界面",
                    "GET  /sdapi/v1/sd-models   模型列表",
                    "GET/POST /sdapi/v1/options 读取 / 切换模型",
                    "GET  /sdapi/v1/samplers    采样器",
                    "GET  /sdapi/v1/upscalers   放大器",
                    "GET  /sdapi/v1/progress    生成进度与预览",
                    "POST /sdapi/v1/txt2img     文生图",
                    "POST /sdapi/v1/img2img     图生图",
                    "POST /sdapi/v1/extra-single-image  超分",
                    "POST /sdapi/v1/interrupt   中断",
                    "POST /sdapi/v1/unload-checkpoint   卸载模型",
                ).forEach {
                    Text(
                        it,
                        style = MaterialTheme.typography.labelSmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    "其余接口（脚本、扩展、Embedding 训练等）后端不支持，已全部去除",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Text(
            "提示：出图前请先在「模型」页启动模型，服务器会把它作为当前 checkpoint 暴露给客户端",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

// ---------------- appearance ----------------

@Composable
private fun AppearanceSettings(container: AppContainer, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    SectionColumn {
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("外观", style = MaterialTheme.typography.titleSmall)
                SwitchRow("动态取色", settings.dynamicColor) {
                    scope.launch { container.settings.setDynamicColor(it) }
                }
                SwitchRow("跟随系统深色模式", settings.followSystemTheme) {
                    scope.launch { container.settings.setFollowSystemTheme(it) }
                }
                if (!settings.followSystemTheme) {
                    SwitchRow("深色模式", settings.darkTheme) {
                        scope.launch { container.settings.setDarkTheme(it) }
                    }
                }
            }
        }
    }
}

// ---------------- generation ----------------

@Composable
private fun GenerationSettings(container: AppContainer, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    SectionColumn {
        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("生成", style = MaterialTheme.typography.titleSmall)
                SwitchRow("显示扩散过程预览", settings.showDiffusionProcess) {
                    scope.launch { container.settings.setShowDiffusionProcess(it) }
                }
                Text(
                    "预览间隔：每 ${settings.showDiffusionStride} 步刷新一次",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(1, 2, 4, 8, 16).forEach { stride ->
                        FilterChip(
                            selected = settings.showDiffusionStride == stride,
                            onClick = { scope.launch { container.settings.setShowDiffusionStride(stride) } },
                            label = { Text(stride.toString()) },
                        )
                    }
                }
            }
        }
    }
}

// ---------------- device monitor ----------------

@Composable
private fun DeviceMonitorSettings(container: AppContainer, settings: AppSettings) {
    val scope = rememberCoroutineScope()
    val stats by container.deviceMonitor.stats.collectAsStateWithLifecycle()
    var shizuku by remember { mutableStateOf(currentShizukuStatus()) }

    val listener = remember {
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == SHIZUKU_REQUEST_CODE) {
                shizuku = currentShizukuStatus().copy(
                    granted = grantResult == PackageManager.PERMISSION_GRANTED,
                )
                container.deviceMonitor.refreshOnce()
            }
        }
    }

    DisposableEffect(Unit) {
        ShellAccess.addPermissionListener(listener)
        onDispose { ShellAccess.removePermissionListener(listener) }
    }

    SectionColumn {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Lock, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("Shizuku 权限", style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.weight(1f))
                    IconButton(onClick = {
                        shizuku = currentShizukuStatus()
                        container.deviceMonitor.refreshOnce()
                    }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
                    }
                }
                val (label, color) = when {
                    shizuku.granted -> "已授权，可读取受限节点" to MaterialTheme.colorScheme.primary
                    !shizuku.installed -> "未安装 Shizuku" to MaterialTheme.colorScheme.outline
                    !shizuku.available -> "Shizuku 未运行，请先启动 Shizuku" to MaterialTheme.colorScheme.tertiary
                    else -> "已运行，尚未授权" to MaterialTheme.colorScheme.tertiary
                }
                Text(label, style = MaterialTheme.typography.bodyMedium, color = color)
                Text(
                    "当前数据来源：${stats.source}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(
                    onClick = {
                        ShellAccess.requestPermission(SHIZUKU_REQUEST_CODE)
                        shizuku = currentShizukuStatus()
                    },
                    enabled = shizuku.installed && shizuku.available && !shizuku.granted,
                ) {
                    Text("请求授权")
                }
                Text(
                    "Shizuku 让本应用以 shell 身份读取温度、GPU 等系统节点，无需 root；" +
                        "不授权时仍会尝试以普通应用身份直读",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        Card {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("温度条", style = MaterialTheme.typography.titleSmall)
                SwitchRow("在「创作」页顶部显示温度", settings.monitorEnabled) {
                    scope.launch { container.settings.setMonitorEnabled(it) }
                }
                Text(
                    "显示 NPU / CPU / GPU 三处 SoC 温度，并随生成进度一起置顶",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        MonitorPreviewCard(stats, settings)

        Text(
            "说明：多数 SoC 没有独立的 NPU 温度传感器，此时 NPU 一栏显示同一颗芯片上的 CPU 温度；" +
                "部分节点需要 Shizuku 授权后才能访问。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

private data class ShizukuStatus(
    val installed: Boolean,
    val available: Boolean,
    val granted: Boolean,
)

private fun currentShizukuStatus(): ShizukuStatus = ShizukuStatus(
    installed = ShellAccess.isShizukuInstalled(),
    available = ShellAccess.isShizukuAvailable(),
    granted = ShellAccess.isPermissionGranted(),
)

@Composable
private fun MonitorPreviewCard(stats: DeviceStats, settings: AppSettings) {
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("当前温度", style = MaterialTheme.typography.titleSmall)
            if (!settings.monitorEnabled) {
                Text(
                    "温度条已关闭",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                return@Column
            }
            if (stats.npuIsFallback) {
                Text(
                    "NPU 回退为 CPU 热区（该设备无独立 NPU 温区）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TempRow("NPU / 芯片", stats.npuTempC)
            TempRow("CPU", stats.cpuTempC)
            TempRow("GPU", stats.gpuTempC)
            stats.hottest?.let { zone ->
                Text(
                    "最高热区：${zone.name} ${"%.1f".format(zone.tempC)}°C",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (stats.elevated) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            stats.note?.let { note ->
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun TempRow(label: String, value: Float?) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(96.dp))
        Text(
            if (value == null) "不可用" else "${"%.1f".format(value)}°C",
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            color = when {
                value == null -> MaterialTheme.colorScheme.outline
                value >= 90f -> MaterialTheme.colorScheme.error
                value >= 70f -> MaterialTheme.colorScheme.tertiary
                else -> MaterialTheme.colorScheme.onSurface
            },
        )
    }
}

// ---------------- shared ----------------

@Composable
private fun SectionColumn(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        content()
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Switch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
    }
}