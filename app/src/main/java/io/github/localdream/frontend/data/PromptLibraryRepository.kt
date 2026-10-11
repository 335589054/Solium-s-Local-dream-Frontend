package io.github.localdream.frontend.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists the user's prompt library (selectable tags + saved prompt combos)
 * as a small JSON file in the app's private storage.
 */
class PromptLibraryRepository(
    private val context: Context,
    private val log: LogRepository,
    private val scope: CoroutineScope,
) {
    private val file = File(context.filesDir, "prompt_library.json")

    private val _library = MutableStateFlow(load())
    val library: StateFlow<PromptLibrary> = _library.asStateFlow()

    /** Imports tags pasted from the AI-generated template text. */
    fun importTags(text: String): Int {
        val parsed = TagListParser.parse(text)
        if (parsed.tags.isEmpty()) {
            log.warn(TAG, "未能从粘贴内容中解析出标签，请确认每行格式为「分类 | 中文 | 英文」")
            return 0
        }
        val before = _library.value.tags.size
        // Keep the incoming order: tag files are normally sorted by how common
        // each tag is, which is more useful than alphabetical.
        val merged = (_library.value.tags + parsed.tags).distinctBy { it.category to it.en }
        _library.value = _library.value.copy(tags = merged)
        persist()
        val added = merged.size - before
        log.info(TAG, "导入标签 $added 条（解析 ${parsed.tags.size} 条，跳过 ${parsed.skipped} 行）")
        return added
    }

    /**
     * Installs the library that ships inside the APK.
     *
     * This replaces the whole tag set instead of merging: importing the bundled
     * file is a "restore the default library" action, and a merge would keep the
     * previous order and hide the new file's ordering entirely.
     */
    fun importBundledTags(): Int {
        val text = runCatching {
            context.assets.open(BUNDLED_TAGS).bufferedReader().use { it.readText() }
        }.getOrElse {
            log.error(TAG, "内置标签库读取失败：${it.message}")
            return 0
        }
        val parsed = TagListParser.parse(text)
        if (parsed.tags.isEmpty()) {
            log.warn(TAG, "内置标签库解析为空")
            return 0
        }
        _library.value = _library.value.copy(tags = parsed.tags)
        persist()
        log.info(TAG, "导入内置标签库 ${parsed.tags.size} 条")
        return parsed.tags.size
    }

    fun addTag(category: String, zh: String, en: String) {
        val english = en.trim()
        if (english.isEmpty()) return
        val cat = category.trim().replace('／', '/').trim('/').ifEmpty { TagListParser.UNCATEGORIZED }
        if (_library.value.tags.any { it.en == english && it.category == cat }) return
        _library.value = _library.value.copy(
            tags = _library.value.tags + PromptTag(cat, zh.trim(), english),
        )
        persist()
        log.info(TAG, "新增标签：$english（$cat）")
    }

    /** Deletes the given tags; used by the single- and multi-select actions. */
    fun removeTags(targets: Collection<PromptTag>) {
        if (targets.isEmpty()) return
        val set = targets.toSet()
        val before = _library.value.tags
        val next = before.filterNot { it in set }
        if (next.size == before.size) return
        _library.value = _library.value.copy(tags = next)
        persist()
        log.info(TAG, "删除 ${before.size - next.size} 个标签")
    }

    /** Deletes a category together with every sub-category beneath it. */
    fun removeCategory(path: String) {
        val before = _library.value.tags
        val next = before.filterNot { it.category == path || it.category.startsWith("$path/") }
        if (next.size == before.size) return
        _library.value = _library.value.copy(tags = next)
        persist()
        log.info(TAG, "删除分类「$path」下的 ${before.size - next.size} 个标签")
    }

    fun clearTags() {
        _library.value = _library.value.copy(tags = emptyList())
        persist()
        log.info(TAG, "已清空标签库")
    }

    /**
     * Writes back a user-arranged order for the tags that sit directly in one
     * category. The reordered list keeps the same slots in the global list, so
     * tags belonging to other categories never move.
     */
    fun reorderCategory(path: String, reordered: List<PromptTag>) {
        val before = _library.value.tags
        val slots = before.indices.filter { before[it].category == path }
        if (slots.size != reordered.size) {
            log.warn(TAG, "排序结果与「$path」的标签数不一致，已忽略")
            return
        }
        val next = before.toMutableList()
        slots.forEachIndexed { i, slot -> next[slot] = reordered[i] }
        if (next == before) return
        _library.value = _library.value.copy(tags = next)
        persist()
        log.info(TAG, "「$path」的标签顺序已更新")
    }

    fun addCombo(name: String, positive: String, negative: String) {
        val combo = PromptCombo(
            name = name.trim().ifEmpty { "未命名" },
            positive = positive,
            negative = negative,
        )
        _library.value = _library.value.copy(combos = _library.value.combos + combo)
        persist()
        log.info(TAG, "收藏提示词：${combo.name}")
    }

    fun removeCombo(id: String) {
        _library.value = _library.value.copy(combos = _library.value.combos.filterNot { it.id == id })
        persist()
    }

    private fun persist() {
        val snapshot = _library.value
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { file.writeText(encode(snapshot)) }
                    .onFailure { log.error(TAG, "提示词库保存失败：${it.message}") }
            }
        }
    }

    private fun load(): PromptLibrary = runCatching {
        if (!file.exists()) return PromptLibrary()
        decode(file.readText())
    }.getOrElse { PromptLibrary() }

    private fun encode(library: PromptLibrary): String = JSONObject().apply {
        put(
            "tags",
            JSONArray().apply {
                library.tags.forEach {
                    put(
                        JSONObject()
                            .put("category", it.category)
                            .put("zh", it.zh)
                            .put("en", it.en),
                    )
                }
            },
        )
        put(
            "combos",
            JSONArray().apply {
                library.combos.forEach {
                    put(
                        JSONObject()
                            .put("id", it.id)
                            .put("name", it.name)
                            .put("positive", it.positive)
                            .put("negative", it.negative)
                            .put("origin", it.origin),
                    )
                }
            },
        )
    }.toString()

    private fun decode(text: String): PromptLibrary {
        val json = JSONObject(text)
        val tags = mutableListOf<PromptTag>()
        json.optJSONArray("tags")?.let { array ->
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                // Tolerate the older single-field "tag" key so libraries written
                // by an earlier build still load.
                val en = obj.optString("en").ifEmpty { obj.optString("tag") }
                if (en.isEmpty()) continue
                tags.add(
                    PromptTag(
                        category = obj.optString("category", TagListParser.UNCATEGORIZED),
                        zh = obj.optString("zh"),
                        en = en,
                    ),
                )
            }
        }
        val combos = mutableListOf<PromptCombo>()
        json.optJSONArray("combos")?.let { array ->
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                combos.add(
                    PromptCombo(
                        id = obj.optString("id").ifEmpty { java.util.UUID.randomUUID().toString() },
                        name = obj.optString("name"),
                        positive = obj.optString("positive"),
                        negative = obj.optString("negative"),
                        origin = obj.optString("origin", "手动"),
                    ),
                )
            }
        }
        return PromptLibrary(tags, combos)
    }

    private companion object {
        const val TAG = "PromptLibrary"
        const val BUNDLED_TAGS = "prompt-library.txt"
    }
}