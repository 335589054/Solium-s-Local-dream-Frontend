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
        val before = _library.value.tags
        val beforeKeys = before.map { it.category to it.en }.toSet()
        // The incoming row wins over the stored one: a tag that was saved with a
        // stray space or a wrong translation has to be fixable by importing a
        // corrected file, which a plain merge would silently refuse to do.
        val incoming = parsed.tags.map { it.category to it.en }.toSet()
        val kept = before.filterNot { (it.category to it.en) in incoming }
        val merged = kept + parsed.tags
        _library.value = _library.value.copy(tags = merged)
        persist()
        val added = merged.count { (it.category to it.en) !in beforeKeys }
        val updated = parsed.tags.size - added
        log.info(TAG, "导入标签：新增 $added 条、更新 $updated 条（跳过 ${parsed.skipped} 行）")
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
        val english = TagListParser.clean(en)
        if (english.isEmpty()) return
        val cat = TagListParser.cleanCategory(category).ifEmpty { TagListParser.UNCATEGORIZED }
        if (_library.value.tags.any { it.en == english && it.category == cat }) return
        _library.value = _library.value.copy(
            tags = _library.value.tags + PromptTag(cat, TagListParser.clean(zh), english),
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
        _library.value = _library.value.copy(tags = emptyList(), categoryOrder = emptyList())
        persist()
        log.info(TAG, "已清空标签库")
    }

    /**
     * Moves one category up or down among the categories that share its parent.
     *
     * Categories are the only thing with a hand-arranged order — the tags
     * inside a category always fall back to alphabetical, which is predictable
     * and needs no juggling.
     */
    fun moveCategory(fullPath: String, direction: Int) {
        val parent = fullPath.substringBeforeLast('/', "").ifEmpty { null }
        val siblings = _library.value.orderedSubCategoriesOf(parent)
        val index = siblings.indexOf(fullPath)
        if (index < 0) return
        val target = index + direction
        if (target !in siblings.indices) return

        // Seed the order with every sibling in the order they are shown right
        // now, otherwise swapping two still-unranked names would be a no-op.
        val order = _library.value.categoryOrder.toMutableList()
        siblings.forEach { if (it !in order) order.add(it) }
        val a = order.indexOf(siblings[index])
        val b = order.indexOf(siblings[target])
        order[a] = siblings[target]
        order[b] = siblings[index]

        _library.value = _library.value.copy(categoryOrder = order)
        persist()
        log.info(TAG, "「$fullPath」上移/下移一位")
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
        decode(file.readText()).cleaned()
    }.getOrElse { PromptLibrary() }

    /**
     * Repairs libraries written by an older build, which stored whatever
     * character the pasted text carried — a leading zero-width or non-breaking
     * space used to survive into every generated prompt.
     */
    private fun PromptLibrary.cleaned(): PromptLibrary {
        val fixed = tags.map {
            PromptTag(
                category = TagListParser.cleanCategory(it.category),
                zh = TagListParser.clean(it.zh),
                en = TagListParser.clean(it.en),
            )
        }.filter { it.en.isNotEmpty() }
        return if (fixed == tags) this else copy(tags = fixed.distinctBy { it.category to it.en })
    }

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
        put("categoryOrder", JSONArray(library.categoryOrder))
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
        val order = mutableListOf<String>()
        json.optJSONArray("categoryOrder")?.let { array ->
            for (i in 0 until array.length()) {
                val path = array.optString(i)
                if (path.isNotEmpty()) order.add(path)
            }
        }
        return PromptLibrary(tags, combos, categoryOrder = order)
    }

    private companion object {
        const val TAG = "PromptLibrary"
        const val BUNDLED_TAGS = "prompt-library.txt"
    }
}