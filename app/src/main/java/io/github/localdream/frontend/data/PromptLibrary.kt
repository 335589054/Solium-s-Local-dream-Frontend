package io.github.localdream.frontend.data

import java.util.UUID

/**
 * A single selectable label from the prompt library.
 *
 * [en] is what actually gets inserted into the prompt (Stable Diffusion tags
 * are English), while [zh] is the Chinese gloss shown for readability and
 * [category] groups tags into tabs.
 */
data class PromptTag(
    val category: String,
    val zh: String,
    val en: String,
)

/** A saved, reusable full prompt (positive + negative). */
data class PromptCombo(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val positive: String,
    val negative: String = "",
    val origin: String = "手动",
)

data class PromptLibrary(
    val tags: List<PromptTag> = emptyList(),
    val combos: List<PromptCombo> = emptyList(),
) {
    val categories: List<String> get() = tags.map { it.category }.distinct().sorted()

    fun tagsIn(category: String): List<PromptTag> = tags.filter { it.category == category }
}

/**
 * Prompt handed to an AI chat assistant so it can convert a loose list of
 * words into the exact line format [TagListParser] expects. The user copies
 * this template, fills in their words, pastes the AI's reply back into the
 * import box.
 */
object TagTemplate {
    const val TEXT: String = """请把下面这些提示词整理成标签清单，严格按以下要求输出：

1. 每行一个标签，用竖线分隔，顺序固定为：分类 | 中文 | 英文
2. 只输出这些数据行，不要表头、不要序号、不要 Markdown 表格、不要任何解释或空行
3. 英文必须使用 Stable Diffusion / Danbooru 通用的英文标签写法，如 1girl、smile、depth of field
4. 中文是该标签准确简短的释义
5. 分类请自行归类，例如：人物、外貌、服饰、动作、表情、场景、光影、氛围、风格、画质

需要整理的提示词：
（把你要整理的中文或英文词粘贴到这里）"""
}

/**
 * Parses the pasted AI output.
 *
 * Accepted line shapes (the separator may be `|`, the full-width `｜`, or a tab):
 *   `分类 | 中文 | 英文`   -> all three fields
 *   `中文 | 英文`          -> category falls back to [UNCATEGORIZED]
 *   `英文`                -> category [UNCATEGORIZED], Chinese left empty
 *
 * Blank lines and `#` comments are ignored, as is a leading header row.
 */
object TagListParser {
    const val UNCATEGORIZED = "未分类"

    private val separators = charArrayOf('|', '｜', '\t')
    private val headerWords = setOf("分类", "类别", "category", "cat", "中文", "英文", "chinese", "english")

    data class Parsed(val tags: List<PromptTag>, val skipped: Int)

    fun parse(text: String): Parsed {
        val tags = mutableListOf<PromptTag>()
        val seen = mutableSetOf<String>()
        var skipped = 0

        text.lineSequence().forEach { rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach

            val parts = line.split(*separators).map { it.trim() }.filter { it.isNotEmpty() }
            // Drop a header row like "分类 | 中文 | 英文" if the assistant adds one.
            if (parts.isEmpty() || isHeader(parts[0])) return@forEach

            val tag = when (parts.size) {
                1 -> PromptTag(UNCATEGORIZED, "", parts[0])
                2 -> PromptTag(UNCATEGORIZED, parts[0], parts[1])
                // Anything past the third column is ignored so stray notes
                // appended by the assistant do not break parsing.
                else -> PromptTag(parts[0], parts[1], parts[2])
            }

            val key = tag.en.ifEmpty { tag.zh }
            if (key.isEmpty()) {
                skipped++
                return@forEach
            }
            if (seen.add(key)) tags.add(tag)
        }

        return Parsed(tags, skipped)
    }

    private fun isHeader(value: String): Boolean = value.lowercase() in headerWords
}