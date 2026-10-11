package io.github.localdream.frontend.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** Where a history entry came from, used for the badge on its card. */
enum class HistoryKind(val label: String) {
    TEXT2IMG("文生图"),
    IMG2IMG("图生图"),
    UPSCALE("超分"),
}

data class HistoryItem(
    val id: String,
    val fileName: String,
    val createdAt: Long,
    val kind: HistoryKind,
    val prompt: String,
    val negativePrompt: String,
    val modelId: String,
    val width: Int,
    val height: Int,
    val seed: Long?,
) {
    fun displayTime(): String = timeFormat.format(Date(createdAt))

    companion object {
        private val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
    }
}

/**
 * Keeps generated images plus the parameters that produced them, so the studio
 * can offer "save to gallery / send to img2img / upscale" later on.
 *
 * Images are stored losslessly as PNG next to a small JSON index in the app's
 * private storage; the oldest entries are dropped once [MAX_ITEMS] is reached.
 */
class HistoryRepository(
    private val context: Context,
    private val log: LogRepository,
    private val scope: CoroutineScope,
) {
    private val dir = File(context.filesDir, "history")
    private val indexFile = File(dir, "index.json")

    private val _items = MutableStateFlow(loadIndex())
    val items: StateFlow<List<HistoryItem>> = _items.asStateFlow()

    fun add(
        bitmap: Bitmap,
        kind: HistoryKind,
        prompt: String,
        negativePrompt: String,
        modelId: String,
        seed: Long?,
    ) {
        val id = java.util.UUID.randomUUID().toString()
        // Upscaled results reach 4096 px a side; lossless PNGs of those would
        // pile up gigabytes, so anything past 2048 px is stored as high-quality
        // JPEG instead.
        val highResolution = maxOf(bitmap.width, bitmap.height) > 2048
        val fileName = if (highResolution) "$id.jpg" else "$id.png"
        val item = HistoryItem(
            id = id,
            fileName = fileName,
            createdAt = System.currentTimeMillis(),
            kind = kind,
            prompt = prompt,
            negativePrompt = negativePrompt,
            modelId = modelId,
            width = bitmap.width,
            height = bitmap.height,
            seed = seed,
        )
        scope.launch {
            val saved = withContext(Dispatchers.IO) {
                runCatching {
                    dir.mkdirs()
                    File(dir, fileName).outputStream().use { output ->
                        if (highResolution) {
                            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, output)
                        } else {
                            bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)
                        }
                    }
                    true
                }.getOrElse {
                    log.error(TAG, "保存历史失败：${it.message}")
                    false
                }
            }
            if (!saved) return@launch

            val next = (_items.value + item).takeLast(MAX_ITEMS)
            val dropped = _items.value + item
            _items.value = next
            // Remove files for entries that fell out of the window.
            withContext(Dispatchers.IO) {
                (dropped - next.toSet()).forEach { File(dir, it.fileName).delete() }
                persistIndex(next)
            }
        }
    }

    fun delete(item: HistoryItem) {
        _items.value = _items.value.filterNot { it.id == item.id }
        scope.launch {
            withContext(Dispatchers.IO) {
                File(dir, item.fileName).delete()
                persistIndex(_items.value)
            }
        }
    }

    fun clear() {
        val old = _items.value
        _items.value = emptyList()
        scope.launch {
            withContext(Dispatchers.IO) {
                old.forEach { File(dir, it.fileName).delete() }
                persistIndex(emptyList())
            }
        }
    }

    fun fileOf(item: HistoryItem): File = File(dir, item.fileName)

    /** Decodes a bounded-size bitmap for grid thumbnails. */
    suspend fun loadThumbnail(item: HistoryItem, maxSize: Int = 512): Bitmap? = withContext(Dispatchers.IO) {
        decode(item, maxSize)
    }

    suspend fun loadFull(item: HistoryItem): Bitmap? = withContext(Dispatchers.IO) {
        decode(item, null)
    }

    private fun decode(item: HistoryItem, maxSize: Int?): Bitmap? = runCatching {
        val file = fileOf(item)
        if (!file.exists()) return null
        if (maxSize == null) return BitmapFactory.decodeFile(file.absolutePath)

        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxSize && bounds.outHeight / (sample * 2) >= maxSize) {
            sample *= 2
        }
        BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }.getOrNull()

    /** Copies the entry into the system gallery. */
    suspend fun saveToGallery(item: HistoryItem): Boolean = withContext(Dispatchers.IO) {
        writeToGallery(fileOf(item), item.fileName, item.createdAt)
    }

    /** Saves an on-screen bitmap (e.g. the current result) to the gallery. */
    suspend fun saveBitmapToGallery(bitmap: Bitmap, name: String): Boolean = withContext(Dispatchers.IO) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            log.warn(TAG, "系统相册保存需要 Android 10 及以上")
            return@withContext false
        }
        runCatching {
            dir.mkdirs()
            val temp = File(dir, "export_$name.png")
            temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val ok = writeToGallery(temp, "export_$name.png", System.currentTimeMillis())
            temp.delete()
            ok
        }.getOrElse {
            log.error(TAG, "保存到相册失败：${it.message}")
            false
        }
    }

    private fun writeToGallery(source: File, fileName: String, timestamp: Long): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            log.warn(TAG, "系统相册保存需要 Android 10 及以上")
            return false
        }
        if (!source.exists()) return false
        return runCatching {
            val isJpeg = fileName.endsWith(".jpg", ignoreCase = true)
            val extension = if (isJpeg) "jpg" else "png"
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "localdream_$timestamp.$extension")
                put(MediaStore.Images.Media.MIME_TYPE, if (isJpeg) "image/jpeg" else "image/png")
                put(
                    MediaStore.Images.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_PICTURES}/LocalDream",
                )
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return false
            context.contentResolver.openOutputStream(uri)?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            }
            log.info(TAG, "已保存到相册：$fileName")
            true
        }.getOrElse {
            log.error(TAG, "保存到相册失败：${it.message}")
            false
        }
    }

    private fun persistIndex(items: List<HistoryItem>) {
        runCatching {
            indexFile.writeText(
                JSONArray().apply {
                    items.forEach { item ->
                        put(
                            JSONObject()
                                .put("id", item.id)
                                .put("fileName", item.fileName)
                                .put("createdAt", item.createdAt)
                                .put("kind", item.kind.name)
                                .put("prompt", item.prompt)
                                .put("negativePrompt", item.negativePrompt)
                                .put("modelId", item.modelId)
                                .put("width", item.width)
                                .put("height", item.height)
                                .put("seed", item.seed ?: JSONObject.NULL),
                        )
                    }
                }.toString(),
            )
        }.onFailure { log.error(TAG, "历史索引写入失败：${it.message}") }
    }

    private fun loadIndex(): List<HistoryItem> = runCatching {
        if (!indexFile.exists()) return emptyList()
        val array = JSONArray(indexFile.readText())
        (0 until array.length()).mapNotNull { i ->
            val obj = array.optJSONObject(i) ?: return@mapNotNull null
            val fileName = obj.optString("fileName")
            if (fileName.isEmpty() || !File(dir, fileName).exists()) return@mapNotNull null
            HistoryItem(
                id = obj.optString("id"),
                fileName = fileName,
                createdAt = obj.optLong("createdAt"),
                kind = runCatching { HistoryKind.valueOf(obj.optString("kind")) }
                    .getOrDefault(HistoryKind.TEXT2IMG),
                prompt = obj.optString("prompt"),
                negativePrompt = obj.optString("negativePrompt"),
                modelId = obj.optString("modelId"),
                width = obj.optInt("width"),
                height = obj.optInt("height"),
                seed = obj.optLong("seed", -1L).takeIf { it != -1L && !obj.isNull("seed") },
            )
        }
    }.getOrElse { emptyList() }

    private companion object {
        const val TAG = "History"
        const val MAX_ITEMS = 100
    }
}