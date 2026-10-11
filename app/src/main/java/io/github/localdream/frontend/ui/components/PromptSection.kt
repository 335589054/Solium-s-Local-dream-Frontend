package io.github.localdream.frontend.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.localdream.frontend.data.PromptToken
import io.github.localdream.frontend.data.PromptTokens

/**
 * A prompt field rendered the way Stable Diffusion front-ends do it: the chosen
 * tags as chips (English on top, translation underneath when known), plus a
 * compact read-only overview that opens a full editor on tap.
 *
 * Tapping a chip wraps the tag in one more pair of parentheses — the usual way
 * to raise its emphasis — while its × peels a level off and removes the tag
 * once no parentheses are left.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PromptSection(
    label: String,
    tokens: List<PromptToken>,
    placeholder: String,
    onIncreaseWeight: (String) -> Unit,
    onDecreaseWeight: (String) -> Unit,
    onEditText: (String) -> Unit,
    onClear: () -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    var editing by remember { mutableStateOf(false) }
    val text = PromptTokens.join(tokens)

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelLarge)
            if (tokens.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                Text(
                    "${tokens.size}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onClear) { Text("清空") }
            }
        }

        if (tokens.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                tokens.forEach { token ->
                    PromptTokenChip(
                        token = token,
                        onIncrease = { onIncreaseWeight(token.id) },
                        onDecrease = { onDecreaseWeight(token.id) },
                    )
                }
            }
            Text(
                "点标签加一层括号，点 × 减一层（减完即移除）",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // The overview stays a single compact block; tapping it opens the editor.
        Surface(
            onClick = { editing = true },
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text.ifBlank { placeholder },
                    style = MaterialTheme.typography.bodySmall,
                    color = if (text.isBlank()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    fontFamily = if (text.isBlank()) FontFamily.Default else FontFamily.Monospace,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                Icon(
                    Icons.Filled.Edit,
                    contentDescription = "编辑",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        supporting?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (editing) {
        PromptEditDialog(
            title = label,
            initial = text,
            onConfirm = {
                onEditText(it)
                editing = false
            },
            onDismiss = { editing = false },
        )
    }
}

/**
 * English on the top half (with its parentheses), translation on the bottom.
 * The body raises emphasis; the trailing × lowers it.
 */
@Composable
private fun PromptTokenChip(token: PromptToken, onIncrease: () -> Unit, onDecrease: () -> Unit) {
    val emphasised = token.weight > 0
    Surface(
        shape = MaterialTheme.shapes.small,
        color = if (emphasised) {
            MaterialTheme.colorScheme.primaryContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        },
        contentColor = if (emphasised) {
            MaterialTheme.colorScheme.onPrimaryContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        },
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(
                Modifier
                    .clickable(onClick = onIncrease)
                    .padding(start = 10.dp, end = 8.dp, top = 5.dp, bottom = 5.dp),
            ) {
                Text(
                    PromptTokens.render(token),
                    style = MaterialTheme.typography.labelMedium,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (token.zh.isNotEmpty()) {
                    Text(
                        token.zh,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                Icons.Filled.Close,
                contentDescription = if (emphasised) "减少一层括号" else "移除",
                modifier = Modifier
                    .clickable(onClick = onDecrease)
                    .padding(horizontal = 6.dp, vertical = 8.dp)
                    .size(14.dp),
            )
        }
    }
}

@Composable
private fun PromptEditDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember(initial) { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("编辑$title") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("用逗号分隔") },
                minLines = 6,
                maxLines = 14,
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
            )
        },
        confirmButton = { TextButton(onClick = { onConfirm(text) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}