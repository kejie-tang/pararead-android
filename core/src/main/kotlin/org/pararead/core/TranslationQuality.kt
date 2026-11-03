package org.pararead.core

/** Ordinary text, not markup or an error state. Stored with the translation so reopening
 * cannot discard an explicitly accepted imperfect result and silently run inference again. */
object TranslationWarnings {
    const val PROTECTED = "protected_token_mismatch"
    const val LANGUAGE = "translation_language_or_source_copy"
    private val suffix = Regex("(?:^| )\\*{5}（([a-z0-9_.:-]{1,160}(?:, [a-z0-9_.:-]{1,160})*)）$")
    private fun match(value: String) = suffix.find(value)
    fun code(reason: String): String = reason.lowercase(java.util.Locale.ROOT)
        .replace(Regex("[^a-z0-9_.:-]+"), "_").trim('_').take(160).ifEmpty { "translation_failed" }
    fun errorCode(error: Exception): String = if (error is org.json.JSONException) "invalid_translation_json"
        else code(error.message ?: error.javaClass.simpleName)
    fun reasons(value: String): List<String> = match(value)?.groupValues?.get(1)?.split(", ") ?: emptyList()
    fun body(value: String): String = match(value)?.let { value.substring(0, it.range.first) } ?: value
    fun append(value: String, additional: List<String>): String {
        val all = (reasons(value) + additional.map(::code)).distinct()
        return body(value) + if (all.isEmpty()) "" else " *****（${all.joinToString(", ")}）"
    }
    /** Fragment warnings belong at the end of the complete sentence, once per code. */
    fun join(parts: List<String>): String = append(parts.joinToString("") { body(it) }, parts.flatMap { reasons(it) })
}

/** Conservative language/copy diagnostics. These are warnings, not a reason to regenerate.
 * Only Chinese targets have a script check; other targets do not guess the language.
 * Unmarked historical bad caches still miss, but marked imperfect results are reusable.
 */
object TranslationQuality {
    private val marker = Regex("__PR[0-9]+__")
    private val word = Regex("[A-Za-z]+[A-Za-z0-9]*")
    private val identifier = Regex("[A-Za-z][A-Za-z0-9._:/+\\-]{0,47}")
    private val chineseTargets = setOf("zh", "zh-cn", "zh-tw", "zh-hans", "zh-hant", "zh-hk", "zh-sg", "zh-mo",
        "中文", "简体中文", "繁体中文")

    fun accepts(source: String, translation: String, config: TranslationConfig): Boolean {
        val body = TranslationWarnings.body(translation)
        if (body.isBlank()) return false
        if (TranslationWarnings.reasons(translation).isNotEmpty()) return true
        return acceptsUnmarked(source, body, config)
    }

    fun forDisplay(source: String, translation: String, config: TranslationConfig, warnings: List<String> = emptyList()): String {
        require(TranslationWarnings.body(translation).isNotBlank()) { "EMPTY_TRANSLATION" }
        val reasons = warnings + if (acceptsUnmarked(source, TranslationWarnings.body(translation), config)) emptyList()
            else listOf(TranslationWarnings.LANGUAGE)
        return TranslationWarnings.append(translation, reasons)
    }

    private fun acceptsUnmarked(source: String, translation: String, config: TranslationConfig): Boolean {
        if (translation.isBlank()) return false
        // The explicitly labelled demo provider intentionally echoes source prose. Its
        // fingerprint is separate from real models; do not break offline UI smoke tests.
        if (config.provider == "mock") return true
        if (config.targetLanguage.lowercase().replace('_', '-') !in chineseTargets) return true
        // Use the same exclusions as the prompt, so equations, URLs, code and quantities
        // do not count as English prose (or as evidence of a Chinese translation).
        fun prose(text: String): String = marker.replace(StructuredTranslation.protect(text).text, " ").trim()
        val original = runCatching { prose(source) }.getOrNull() ?: return false
        // Unknown protected markers already carry their own diagnostic; they are not prose.
        val result = runCatching { prose(marker.replace(translation, " ")) }.getOrNull() ?: return false
        fun han(text: String) = text.codePoints().filter { Character.UnicodeScript.of(it) == Character.UnicodeScript.HAN }.count()
        val originalHan = han(original)
        val resultHan = han(result)
        val originalWords = word.findAll(original).map { it.value }.toList()
        val resultWords = word.findAll(result).map { it.value }.toList()
        fun neutral(text: String, words: List<String>, raw: String): Boolean {
            if (text.none { it.isLetter() }) return true
            val compact = raw.trim()
            if (identifier.matches(compact) && compact.last().isLetterOrDigit() &&
                (compact.any(Char::isDigit) || compact.any { it in "._:/+-" })) return true
            // Also allow short, unwrapped mathematical expressions, not prose equations.
            if (text.any { it in "=+−×÷∑∫^<>" } && words.all { it.length <= 3 }) return true
            // Short proper names/model identifiers are often intentionally untranslated.
            return text.length <= 48 && words.size in 1..4 && words.all {
                it.any(Char::isUpperCase) || it.any(Char::isDigit) || it in setOf("von", "van", "de", "del", "da")
            } && !text.endsWith('.') && !text.endsWith('!') && !text.endsWith('?')
        }
        if (resultHan == 0L) return originalHan == 0L &&
            neutral(original, originalWords, source) && neutral(result, resultWords, translation)

        val originalLetters = originalWords.sumOf { it.length }
        val resultLetters = resultWords.sumOf { it.length }
        // Do not reject Chinese prose containing English terms. Require substantial English
        // on both sides, very little Han, and high word overlap in BOTH directions.
        if (originalWords.size >= 6 && resultWords.size >= 6 && originalLetters >= 30 &&
            originalHan <= 2 && resultHan * 8 < resultLetters) {
            val remaining = originalWords.groupingBy { it.lowercase() }.eachCount().toMutableMap()
            val copied = resultWords.count {
                val key = it.lowercase()
                val count = remaining[key] ?: 0
                if (count > 0) remaining[key] = count - 1
                count > 0
            }
            if (copied >= originalWords.size * 0.8 && copied >= resultWords.size * 0.8) return false
        }
        return true
    }

    fun requireValid(source: String, translation: String, config: TranslationConfig) {
        require(accepts(source, translation, config)) { "TRANSLATION_LANGUAGE_OR_SOURCE_COPY" }
    }
}
