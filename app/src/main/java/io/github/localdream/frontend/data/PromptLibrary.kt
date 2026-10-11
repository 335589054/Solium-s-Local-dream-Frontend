package io.github.localdream.frontend.data

import java.util.UUID

/**
 * A single selectable label from the prompt library.
 *
 * [en] is what actually gets inserted into the prompt (Stable Diffusion tags
 * are English), while [zh] is the Chinese gloss shown for readability.
 *
 * [category] is a `/`-separated path of up to three levels, e.g.
 * `人物/脸部/眼睛`. Browsing walks that tree one level at a time, and tags that
 * sit directly on a branch which also has sub-branches are grouped under
 * `未分类` when that branch is open.
 */
data class PromptTag(
    val category: String,
    val zh: String,
    val en: String,
) {
    /** Path split into its levels. */
    val levels: List<String> get() = category.split('/').filter { it.isNotBlank() }

    /** How deep this tag sits, 1 for a top-level category. */
    val depth: Int get() = levels.size
}

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
    /**
     * Full paths of the categories the user has arranged by hand. Categories
     * missing from this list keep their alphabetical spot, so new imports never
     * land in a surprising place.
     */
    val categoryOrder: List<String> = emptyList(),
) {
    /** Top-level categories, alphabetically. */
    val categories: List<String>
        get() = tags.mapNotNull { it.levels.firstOrNull() }.distinct().sorted()

    /** Alias kept for the picker, which browses a flat list of paths. */
    val categoryPaths: List<String> get() = categories

    /**
     * Names of the branches directly under [path]; the top level when [path] is
     * blank. Tags hanging directly off [path] do not count as branches.
     */
    fun subCategoriesOf(path: String?): List<String> {
        val prefix = if (path.isNullOrBlank()) "" else "$path/"
        return tags.mapNotNull { tag ->
            if (!tag.category.startsWith(prefix)) return@mapNotNull null
            val rest = tag.category.removePrefix(prefix)
            if (rest.isEmpty()) return@mapNotNull null
            rest.substringBefore('/').takeIf { it.isNotEmpty() }
        }.distinct().sorted()
    }

    /** Full paths of the branches directly under [path], alphabetically. */
    fun subCategoryPathsOf(path: String?): List<String> {
        val prefix = if (path.isNullOrBlank()) "" else "$path/"
        return tags.mapNotNull { tag ->
            if (!tag.category.startsWith(prefix)) return@mapNotNull null
            val rest = tag.category.removePrefix(prefix)
            if (rest.isEmpty()) return@mapNotNull null
            val name = rest.substringBefore('/')
            if (name.isEmpty()) null else prefix + name
        }.distinct().sorted()
    }

    /**
     * The branches under [path] as the user arranged them: hand-arranged ones
     * come first in [categoryOrder], the rest stay alphabetical.
     */
    fun orderedSubCategoriesOf(path: String?): List<String> {
        val paths = subCategoryPathsOf(path)
        if (categoryOrder.isEmpty()) return paths
        val rank = categoryOrder.withIndex().associate { (index, value) -> value to index }
        return paths.sortedBy { rank[it] ?: Int.MAX_VALUE }
    }

    /** Tags sitting exactly on [path], alphabetically by their English tag. */
    fun directTagsOf(path: String?): List<PromptTag> =
        if (path.isNullOrBlank()) emptyList() else tagsIn(path)

    /** Tags on [path] and everything below it. */
    fun tagsUnder(path: String): List<PromptTag> =
        tags.filter { it.category == path || it.category.startsWith("$path/") }

    fun tagsIn(category: String): List<PromptTag> =
        tags.filter { it.category == category }.sortedBy { it.en.lowercase() }

    /** True when opening [path] should show branches, not just a tag grid. */
    fun hasBranches(path: String): Boolean = subCategoriesOf(path).isNotEmpty()
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
3. 分类写成「一级/二级」的形式，斜杠分隔，例如：人物/眼睛、服饰/裙子、场景/城市
4. 分类只能从下面这份清单里选，不要自创：
人物/ 对象、身份、二次元角色、年龄、皮肤、身材、脸型、头发、面部、耳朵、眉毛、眼睛、瞳孔、鼻子、嘴巴、牙齿、舌头、指甲、肩部、胸部、腰部、腹部、翅膀
服饰/ 正装、风格、休闲装、运动服、泳装、制服、上衣、外套、腰部、盔甲、裙子、与裙子互动、裤子、与裤子互动、袜子、与袜子互动、材质、装饰、花纹、鞋子、靴子、鞋底、领口、围巾、眼镜、面具、手臂、手、手套、耳饰、头饰、帽子、发饰、小装饰、首饰
表情动作/ 笑、哭、不开心、蔑视、生气、其他表情、基础动作、手部动作、腿部动作、其他动作
画面/ 画质、艺术风格、艺术类型、艺术派系、艺术家风格、光照、写实、素描、画笔、颜色、背景
环境/ 季节、天气、大自然、水、天空、云、氛围
场景/ 室外、城市、室内、地板、家具、床上用品、浴室
物品/ 学习用品、数码设备、餐具、乐器、其它物品、武器、食物、动物、植物
镜头/ 镜头、特写镜头、其他构图、镜头角度、效果、主角动作
汉服/ 唐风、宋风、明风、上衣、裙子、领子、装饰
反向提示词/ 画面、人、Embeddings
5. 英文必须使用 Stable Diffusion / Danbooru 通用写法，小写、多词之间用下划线，如 1girl、blue_eyes、depth_of_field
6. 中文是该标签准确简短的释义

示例：
人物/眼睛 | 蓝眼睛 | blue_eyes
服饰/裙子 | 百褶裙 | pleated_skirt
反向提示词/画面 | 低画质 | low_quality

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

    /**
     * Characters that ride along with copy-pasted text and are invisible in the
     * UI, yet still get tokenised by the backend — a zero-width space or a
     * non-breaking space is not `Char.isWhitespace`, so `trim()` leaves it in
     * place and it ends up costing a token in front of the tag.
     */
    private const val INVISIBLE = "\u00A0\u200B\u200C\u200D\u2060\u3000\uFEFF"

    /** Drops invisible characters and the whitespace around the field. */
    fun clean(value: String): String = value.filterNot { it in INVISIBLE }.trim()

    /** A category path: invisible characters gone, every level trimmed. */
    fun cleanCategory(value: String): String = clean(value)
        .replace('／', '/')
        .split('/')
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .joinToString("/")

    data class Parsed(val tags: List<PromptTag>, val skipped: Int)

    fun parse(text: String): Parsed {
        val tags = mutableListOf<PromptTag>()
        val seen = mutableSetOf<String>()
        var skipped = 0

        // A file saved by a Windows editor often carries a UTF-8 BOM, which
        // would otherwise glue itself onto the first category name.
        text.removePrefix("\uFEFF").lineSequence().forEach { rawLine ->
            val line = clean(rawLine)
            if (line.isEmpty() || line.startsWith("#")) return@forEach

            // Split and clean, but keep empty middle columns: a row such as
            // "分类 |  | blue_eyes" is still a valid three-column entry whose
            // translation is simply missing. Only trailing empties (a stray
            // separator at the end of the line) are dropped.
            val parts = line.split(*separators).map { clean(it) }.dropLastWhile { it.isEmpty() }
            // Drop a header row like "分类 | 中文 | 英文" if the assistant adds one.
            if (parts.isEmpty() || isHeader(parts[0])) return@forEach

            val tag = when (parts.size) {
                1 -> PromptTag(UNCATEGORIZED, "", parts[0])
                2 -> PromptTag(UNCATEGORIZED, parts[0], parts[1])
                // Anything past the third column is ignored so stray notes
                // appended by the assistant do not break parsing.
                else -> PromptTag(cleanCategory(parts[0]), parts[1], parts[2])
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