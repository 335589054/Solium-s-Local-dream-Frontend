package io.github.localdream.frontend.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.localdream.frontend.data.DeviceStats

/** Silicon this side of 90 °C is normal under sustained load. */
private const val HOT_TEMP_C = 90f
private const val WARM_TEMP_C = 70f

/**
 * A slim sticky bar pinned above the studio content: SoC temperatures on the
 * first line, and (while running) the progress bar with its counter underneath
 * so the two never get squeezed onto the same line.
 */
@Composable
fun MonitorBar(
    stats: DeviceStats,
    isGenerating: Boolean,
    isUpscaling: Boolean,
    progress: Float,
    currentStep: Int,
    totalSteps: Int,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
        tonalElevation = 3.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            Row(
                Modifier.fillMaxWidth().heightIn(min = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                TempChip("NPU", stats.npuTempC, stats)
                TempChip("CPU", stats.cpuTempC, stats)
                TempChip("GPU", stats.gpuTempC, stats)
            }

            if (isGenerating || isUpscaling) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { if (isUpscaling) 1f else progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                )
                Spacer(Modifier.height(2.dp))
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        when {
                            isUpscaling -> "正在超分…"
                            totalSteps > 0 -> "第 $currentStep / $totalSteps 步"
                            else -> "生成中…"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    if (isGenerating && totalSteps > 0) {
                        Text(
                            "${(progress * 100).toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun TempChip(label: String, value: Float?, stats: DeviceStats) {
    val color = tempColor(value, stats)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.Thermostat,
            contentDescription = null,
            modifier = Modifier.size(14.dp),
            tint = color,
        )
        Spacer(Modifier.width(4.dp))
        Text(
            if (value == null) "$label --" else "$label ${"%.1f".format(value)}°C",
            style = MaterialTheme.typography.labelMedium,
            color = color,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

@Composable
internal fun tempColor(value: Float?, stats: DeviceStats): Color = when {
    value == null -> MaterialTheme.colorScheme.outline
    value >= HOT_TEMP_C -> MaterialTheme.colorScheme.error
    value >= WARM_TEMP_C -> MaterialTheme.colorScheme.tertiary
    stats.elevated -> MaterialTheme.colorScheme.tertiary
    else -> MaterialTheme.colorScheme.primary
}