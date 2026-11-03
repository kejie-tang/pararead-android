package org.pararead.core

/** Ordinary runtime diagnostics, not markup or translation-quality judgements. */
object TranslationWarnings {
    // Historical quality annotations are readable for cache compatibility, but no longer shown.
    private val obsolete = setOf("protected_token_mismatch", "translation_language_or_source_copy")
    private val suffix = Regex("(?: ?\\*{5})?（([a-z0-9_.:-]{1,160}(?:, [a-z0-9_.:-]{1,160})*)）$")
    private fun split(value: String): Pair<String, List<String>> {
        var body = value
        val reasons = mutableListOf<String>()
        while (true) {
            val match = suffix.find(body.trimEnd()) ?: break
            reasons.addAll(0, match.groupValues[1].split(", "))
            body = body.substring(0, match.range.first).trimEnd()
        }
        return body to reasons.distinct()
    }
    fun code(reason: String): String = reason.lowercase(java.util.Locale.ROOT)
        .replace(Regex("[^a-z0-9_.:-]+"), "_").trim('_').take(160).ifEmpty { "translation_failed" }
    fun errorCode(error: Exception): String = if (error is org.json.JSONException) "invalid_translation_json"
        else code(error.message ?: error.javaClass.simpleName)
    fun reasons(value: String): List<String> = split(value).second
    fun body(value: String): String = split(value).first
    fun append(value: String, additional: List<String>): String {
        val (body, existing) = split(value)
        val all = (existing + additional.map(::code)).filterNot { it in obsolete }.distinct()
        return body + if (all.isEmpty()) "" else "（${all.joinToString(", ")}）"
    }
    /** Fragment runtime diagnostics belong at the end of the complete sentence. */
    fun join(parts: List<String>): String = append(parts.joinToString("") { body(it) }, parts.flatMap { reasons(it) })
}

/** Compatibility gate only: empty output is unusable; linguistic accuracy is not judged. */
@Suppress("UNUSED_PARAMETER")
object TranslationQuality {
    fun accepts(source: String, translation: String, config: TranslationConfig): Boolean =
        TranslationWarnings.body(translation).isNotBlank()

    fun forDisplay(source: String, translation: String, config: TranslationConfig, warnings: List<String> = emptyList()): String {
        require(TranslationWarnings.body(translation).isNotBlank()) { "EMPTY_TRANSLATION" }
        return TranslationWarnings.append(translation, warnings)
    }

    fun requireValid(source: String, translation: String, config: TranslationConfig) {
        require(accepts(source, translation, config)) { "EMPTY_TRANSLATION" }
    }
}
