package io.github.localdream.frontend.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import io.github.localdream.frontend.data.ConnPhase
import io.github.localdream.frontend.data.ConnState
import io.github.localdream.frontend.remote.RemoteProtocol

@Composable
fun ConnectionChip(state: ConnState, onClick: () -> Unit) {
    val (label, dotColor) = when (state.phase) {
        ConnPhase.CONNECTED -> when {
            state.unstable -> "连接不稳定" to Color(0xFFF9A825)
            state.status?.state == RemoteProtocol.STATE_RUNNING -> "已就绪" to Color(0xFF2E7D32)
            state.status?.state == RemoteProtocol.STATE_STARTING -> "启动中" to Color(0xFFF9A825)
            state.status?.state == RemoteProtocol.STATE_ERROR -> "后端异常" to MaterialTheme.colorScheme.error
            else -> "已连接" to Color(0xFF2E7D32)
        }

        ConnPhase.CONNECTING -> "连接中" to Color(0xFFF9A825)
        // The repository keeps retrying in the background, so this is a
        // transient state rather than a dead end.
        ConnPhase.FAILED -> "重连中" to Color(0xFFF9A825)
        ConnPhase.DISCONNECTED -> "未连接" to MaterialTheme.colorScheme.outline
    }

    AssistChip(
        onClick = onClick,
        label = { Text(label) },
        leadingIcon = {
            Surface(
                modifier = Modifier.size(10.dp),
                shape = CircleShape,
                color = dotColor,
            ) {}
        },
        colors = AssistChipDefaults.assistChipColors(),
        modifier = Modifier.padding(end = 8.dp),
    )
}

@Composable
fun StatusDot(color: Color, modifier: Modifier = Modifier) {
    Surface(modifier = modifier.size(8.dp), shape = CircleShape, color = color) {}
}