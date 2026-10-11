package io.github.localdream.frontend.data

import java.util.UUID

/**
 * One entry of a prompt field. [en] is the text that is actually sent to the
 * backend; [zh] is an optional translation shown under it in the UI, filled in
 * when the entry came from (or matches) the tag library.
 *
 * [weight] is the number of parentheses wrapped around the tag — the Stable
 * Diffusion convention for emphasis, where `(tag)` is a little stronger and
 * `((tag))` stronger still.
 */
data class PromptToken(
    val en: String,
    val zh: String = "",
    val weight: Int = 0,
    val id: String = UUID.randomUUID().toString(),
)

/**
 * Converts between the structured token list shown as chips in the studio and
 * the plain comma-separated prompt string the backend expects.
 */
object PromptTokens {
    /** Emphasis nesting is capped so a tag cannot be inflated by accident. */
    const val MAX_WEIGHT = 6

    /** Splits free-form text into tokens, reusing translations when known. */
    fun parse(text: String, lookup: (String) -> String? = { null }): List<PromptToken> {
        val parts = text.split(',', '，').map { it.trim() }.filter { it.isNotEmpty() }
        // Collapse duplicates the way the editor does, so pasting a prompt that
        // already contains a tag does not create a second chip for it.
        val seen = mutableSetOf<String>()
        return parts.mapNotNull { part ->
            val (tag, weight) = stripParens(part)
            if (tag.isEmpty() || !seen.add(tag)) return@mapNotNull null
            PromptToken(en = tag, zh = lookup(tag) ?: "", weight = weight)
        }
    }

    /** Serialises tokens into the comma-separated prompt string. */
    fun join(tokens: List<PromptToken>): String = tokens.joinToString(", ") { render(it) }

    /** Merges a tag into an existing list, ignoring an exact duplicate. */
    fun add(tokens: List<PromptToken>, en: String, zh: String): List<PromptToken> {
        if (tokens.any { it.en == en }) return tokens
        return tokens + PromptToken(en = en, zh = zh)
    }

    fun remove(tokens: List<PromptToken>, id: String): List<PromptToken> =
        tokens.filterNot { it.id == id }

    /** Adds one parenthesis level to a token, clamped at [MAX_WEIGHT]. */
    fun increaseWeight(tokens: List<PromptToken>, id: String): List<PromptToken> =
        tokens.map { if (it.id == id) it.copy(weight = (it.weight + 1).coerceAtMost(MAX_WEIGHT)) else it }

    /** Removes one parenthesis level; callers delete the token at zero. */
    fun decreaseWeight(tokens: List<PromptToken>, id: String): List<PromptToken> =
        tokens.map { if (it.id == id) it.copy(weight = (it.weight - 1).coerceAtLeast(0)) else it }

    /** How the token looks in the prompt string. */
    fun render(token: PromptToken): String =
        if (token.weight <= 0) token.en else "(".repeat(token.weight) + token.en + ")".repeat(token.weight)

    /**
     * Peels matching parentheses off a tag.
     *
     * Something like `(tag:1.2)` uses the explicit weight syntax instead of
     * nesting, so it is left untouched.
     */
    private fun stripParens(value: String): Pair<String, Int> {
        var text = value.trim()
        var weight = 0
        while (text.length > 2 && text.startsWith("(") && text.endsWith(")")) {
            val inner = text.substring(1, text.length - 1).trim()
            if (inner.isEmpty() || inner.contains(':') || inner.contains('(')) break
            text = inner
            weight++
        }
        return text to weight.coerceAtMost(MAX_WEIGHT)
    }
}