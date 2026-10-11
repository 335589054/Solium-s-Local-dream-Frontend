package io.github.localdream.frontend.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.localdream.frontend.AppContainer
import io.github.localdream.frontend.data.AppSettings
import io.github.localdream.frontend.data.ConnPhase
import kotlinx.coroutines.launch

@Composable
fun ConnectScreen(
    container: AppContainer,
    settings: AppSettings,
    onConnected: () -> Unit,
    onBack: () -> Unit,
) {
    val connection = container.connection
    val state by connection.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    var host by remember { mutableStateOf(settings.host) }
    var portText by remember { mutableStateOf(settings.controlPort.toString()) }

    // Only leave automatically when *this* screen started a connect that then
    // succeeded. Returning on any CONNECTED state would bounce the user straight
    // back out while the link is merely unstable, making the entry look dead.
    var awaitingConnect by remember { mutableStateOf(false) }
    LaunchedEffect(state.phase) {
        if (awaitingConnect && state.phase == ConnPhase.CONNECTED) {
            awaitingConnect = false
            onConnected()
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
            }
            Text("连接主机", style = MaterialTheme.typography.titleLarge)
        }

        Card {
            Column(
                Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("主机地址") },
                    supportingText = { Text("本机填 127.0.0.1，局域网填主机 IP") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = portText,
                    onValueChange = { input -> portText = input.filter { it.isDigit() } },
                    label = { Text("控制端口") },
                    supportingText = { Text("local-dream 受控模式默认 8808") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            val port = portText.toIntOrNull() ?: 8808
                            scope.launch {
                                container.settings.setHost(host)
                                container.settings.setControlPort(port)
                                awaitingConnect = true
                                connection.connect(host, port)
                            }
                        },
                        enabled = state.phase != ConnPhase.CONNECTING,
                    ) {
                        Text("连接")
                    }
                    OutlinedButton(onClick = { connection.disconnect() }) {
                        Text("断开")
                    }
                    if (state.phase == ConnPhase.CONNECTING) {
                        CircularProgressIndicator(Modifier.width(24.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("正在握手…", style = MaterialTheme.typography.bodySmall)
                    }
                }

                state.error?.let {
                    Text(
                        it,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }

        state.hostInfo?.let { info ->
            Card {
                Column(
                    Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("主机信息", style = MaterialTheme.typography.titleSmall)
                    Text("设备：${info.deviceName}", style = MaterialTheme.typography.bodyMedium)
                    Text("版本：${info.version}", style = MaterialTheme.typography.bodyMedium)
                    Text("协议：${info.protocol}", style = MaterialTheme.typography.bodyMedium)
                    val catalog = state.catalog
                    if (catalog != null) {
                        Text(
                            "模型 ${catalog.models.size} 个 · 放大器 ${catalog.upscalers.size} 个",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        Text(
            "提示：主机需以「受控模式」运行，并允许本机访问其 8808 与 8081 端口",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}