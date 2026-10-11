package io.github.localdream.frontend.ui.components

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties

/**
 * Actions offered for an image, wherever it comes from. Both the studio's
 * current result and a history entry fill this in, so the two always show one
 * identical button layout instead of drifting apart.
 */
data class ImageActions(
    val onSaveToGallery: (() -> Unit)? = null,
    val onSendToImg2Img: (() -> Unit)? = null,
    val onSendToUpscale: (() -> Unit)? = null,
    val onApplyPrompt: (() -> Unit)? = null,
)

/**
 * Full-screen image viewer with gallery-style pinch zoom and pan: drag while
 * zoomed in, double-tap to toggle between fit and 2.5x.
 */
@Composable
fun ImageViewerDialog(
    bitmap: Bitmap,
    title: String,
    meta: String,
    prompt: String?,
    actions: ImageActions,
    onDismiss: () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }

    fun reset() {
        scale = 1f
        offset = Offset.Zero
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(color = Color.Black.copy(alpha = 0.96f), modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = Color.White,
                        )
                    }
                    Text(title, color = Color.White, style = MaterialTheme.typography.titleSmall)
                    Spacer(Modifier.weight(1f))
                    if (scale > 1.01f) {
                        TextButton(onClick = { reset() }) { Text("复位", color = Color.White) }
                    }
                }

                Box(
                    Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .pointerInput(bitmap) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                scale = (scale * zoom).coerceIn(1f, 8f)
                                offset = if (scale > 1f) offset + pan else Offset.Zero
                            }
                        }
                        .pointerInput(bitmap) {
                            detectTapGestures(
                                onDoubleTap = { if (scale > 1.01f) reset() else scale = 2.5f },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer(
                                scaleX = scale,
                                scaleY = scale,
                                translationX = offset.x,
                                translationY = offset.y,
                            ),
                    )
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceContainerHigh,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            meta,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (!prompt.isNullOrBlank()) {
                            Text(
                                prompt,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 3,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                        ActionRow(actions)
                        Text(
                            "双指缩放 / 拖动查看，双击放大或复位",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

/** One consistent set of buttons for every image, wherever it came from. */
@Composable
private fun ActionRow(actions: ImageActions) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (actions.onSendToImg2Img != null || actions.onSendToUpscale != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.onSendToImg2Img?.let {
                    Button(onClick = it, modifier = Modifier.weight(1f)) { Text("图生图") }
                }
                actions.onSendToUpscale?.let {
                    Button(onClick = it, modifier = Modifier.weight(1f)) { Text("超分") }
                }
            }
        }
        if (actions.onSaveToGallery != null || actions.onApplyPrompt != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                actions.onSaveToGallery?.let {
                    OutlinedButton(onClick = it, modifier = Modifier.weight(1f)) { Text("保存至图库") }
                }
                actions.onApplyPrompt?.let {
                    OutlinedButton(onClick = it, modifier = Modifier.weight(1f)) { Text("套用提示词") }
                }
            }
        }
    }
}