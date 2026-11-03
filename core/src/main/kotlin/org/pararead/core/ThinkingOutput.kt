package org.pararead.core

/** Separates protocol reasoning wrappers from literal text; never rewrites JSON string values. */
internal object ThinkingOutput {
    data class Structured(val text: String, val hadThinking: Boolean)
    fun structured(raw: String): Structured {
        val clean = StringBuilder()
        var cursor = 0
        var quoted = false
        var escaped = false
        var hadThinking = false
        while (cursor < raw.length) {
            val ch = raw[cursor]
            if (quoted) {
                clean.append(ch); cursor++
                if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '"') quoted = false
            } else if (raw.startsWith("<think>", cursor)) {
                hadThinking = true
                var depth = 1
                cursor += 7
                while (cursor < raw.length && depth > 0) {
                    when {
                        raw.startsWith("<think>", cursor) -> { depth++; cursor += 7 }
                        raw.startsWith("</think>", cursor) -> { depth--; cursor += 8 }
                        else -> cursor++
                    }
                }
                // Do not join tokens across a removed wrapper. An unfinished block ends recovery.
                val before = clean.lastOrNull { !it.isWhitespace() }
                clean.append(' ')
                if (depth > 0) break
                var next = cursor
                while (next < raw.length && raw[next].isWhitespace()) next++
                val after = raw.getOrNull(next)
                // Cutting reasoning out of an unquoted value must not create a new token/value.
                if (before != null && after != null && before !in "{}[],:\"" && after !in "{}[],:\"") break
            } else if (raw.startsWith("</think>", cursor)) {
                // Without an opening boundary the preceding text could itself be reasoning.
                return Structured("", true)
            } else {
                clean.append(ch); cursor++
                if (ch == '"') quoted = true
            }
        }
        return Structured(clean.toString(), hadThinking)
    }

    private val tag = Regex("</?think>")
    private val literal = Regex("```[\\s\\S]*?```|`[^`\\r\\n]+`|\"(?:[^\"\\\\\\r\\n]|\\\\.)*\"|'(?:[^'\\\\\\r\\n]|\\\\.)*'")
    /** Text mode has no JSON boundary: allow only complete literal snippets owned by the source,
     * including delimiters and multiplicity. Merely quoting newly generated reasoning is not enough. */
    fun textHasUntrustedTags(raw: String, source: String): Boolean {
        val tags = tag.findAll(raw).toList()
        if (tags.isEmpty()) return false
        val remaining = literal.findAll(source).filter { tag.containsMatchIn(it.value) }
            .groupingBy { it.value }.eachCount().toMutableMap()
        val owned = literal.findAll(raw).filter { match ->
            val count = remaining[match.value] ?: 0
            if (count == 0) false else { remaining[match.value] = count - 1; true }
        }.map { it.range }.toList()
        return tags.any { match -> owned.none { match.range.first >= it.first && match.range.last <= it.last } }
    }
}
