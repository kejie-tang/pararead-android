package org.pararead.core

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.security.MessageDigest
import java.text.Normalizer

data class SourceSentence(val id: String, val text: String, val group: String)
data class TranslationConfig(
    val provider: String = "llama", val model: String = "qwen3:1.7b", val modelHash: String = "unloaded",
    val sourceLanguage: String = "auto", val targetLanguage: String = "zh-CN", val glossary: String = "{}",
    val batchSize: Int = 4, val maxChars: Int = 3000,
) {
    // Preserve native cache keys written before the default display name became the Ollama tag.
    fun fingerprint() = listOf(provider, if (provider == "llama" && model == "qwen3:1.7b") "Qwen3-1.7B" else model, modelHash, sourceLanguage, targetLanguage, canonicalGlossary(glossary),
        "prompt=6", "validator=6", "recovery=sentence-text-v1", "template=model-chat-v2", "nativeGrammar=exact-translation-json-v3").joinToString("\u0000")
}
fun sha256(text: String): String = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
fun contentKey(text: String, config: TranslationConfig): String = sha256(Normalizer.normalize(text, Normalizer.Form.NFC).replace(Regex("\\s+"), " ").trim() + "\u0000" + config.fingerprint())
fun canonicalGlossary(raw: String): String {
    fun canonical(j: JSONObject): String = j.keys().asSequence().toList().sorted().joinToString(",", "{", "}") {
        val v = j.get(it)
        JSONObject.quote(it) + ":" + if (v is JSONObject) canonical(v) else JSONObject.quote(v.toString())
    }
    return canonical(JSONObject(raw))
}
sealed interface ProviderEvent {
    data object Restart : ProviderEvent
    data class Delta(val text: String) : ProviderEvent
    data class Complete(val text: String) : ProviderEvent
}
enum class ModelOutputFormat { TRANSLATION_JSON, TEXT }
data class ModelPrompt(val system: String, val user: String, val expectedResults: Int = 0,
    val outputFormat: ModelOutputFormat = ModelOutputFormat.TRANSLATION_JSON)
interface TranslationProvider : AutoCloseable {
    fun generate(prompt: ModelPrompt): Flow<ProviderEvent>
    override fun close() {}
}
class MockTranslationProvider : TranslationProvider {
    override fun generate(prompt: ModelPrompt) = flow {
        delay(20)
        val source = JSONObject(prompt.user.substringBefore("\n/no_think")).getJSONArray("sentences")
        val result = JSONArray()
        for (i in 0 until source.length()) {
            val s = source.getJSONObject(i)
            result.put(JSONObject().put("id", s.getString("id")).put("translation", "【模拟译文】" + s.getString("text")))
        }
        emit(ProviderEvent.Complete(JSONObject().put("translations", result).toString()))
    }
}
data class ProtectedText(val text: String, val tokens: List<String>, val firstToken: Int = 0) {
    val markers get() = tokens.indices.map { "__PR${it + firstToken}__" }
    /** Best effort within this sentence only: never invent a missing citation, or map a
     * foreign/renumbered marker to a different sentence's token. Unknown markers stay visible. */
    fun forDisplay(value: String, source: String, config: TranslationConfig, errors: List<String> = emptyList()): String {
        val pattern = Regex("__PR\\d+__")
        val placeholders = pattern.findAll(value).map { it.value }.toList()
        val warnings = if (placeholders.sorted() == markers.sorted()) emptyList() else listOf(TranslationWarnings.PROTECTED)
        val replacements = markers.zip(tokens).toMap()
        val restored = pattern.replace(value) { replacements[it.value] ?: it.value }
        return TranslationQuality.forDisplay(source, restored, config, errors + warnings)
    }
    fun restore(value: String): String {
        val placeholders = Regex("__PR\\d+__").findAll(value).map { it.value }.toList()
        require(placeholders.sorted() == markers.sorted()) { "PROTECTED_TOKEN_MISMATCH" }
        var restored = value
        tokens.forEachIndexed { i, t -> restored = restored.replace("__PR${i + firstToken}__", t) }
        return restored
    }
}
data class TranslationValidation(val accepted: List<Pair<SourceSentence, String>>, val rejected: List<Pair<SourceSentence, String>>)
object StructuredTranslation {
    fun fragments(text: String, limit: Int = 1200): List<String> {
        val protected = protect(text)
        val chunks = mutableListOf<String>(); val current = StringBuilder()
        for (unit in Regex("__PR[0-9]+__|.", RegexOption.DOT_MATCHES_ALL).findAll(protected.text)) {
            if (current.isNotEmpty() && current.length + unit.value.length > limit) { chunks += current.toString(); current.setLength(0) }
            current.append(unit.value)
        }
        if (current.isNotEmpty()) chunks += current.toString()
        return chunks.map { chunk -> var value = chunk; protected.tokens.forEachIndexed { i, token -> value = value.replace("__PR${i}__", token) }; value }
    }
    fun drafts(raw: String, sentences: List<SourceSentence>, values: List<ProtectedText>): List<Pair<SourceSentence, String>> =
        salvage(raw, sentences, values, TranslationConfig(provider = "mock"), "draft").accepted
            .map { (source, text) -> source to TranslationWarnings.body(text) }
    /** Recover only explicit, unambiguous sentence rows from a failed/truncated envelope.
     * Never assign another ID's output, JSON scaffolding or reasoning as a translation. */
    fun salvage(raw: String, sentences: List<SourceSentence>, values: List<ProtectedText>,
        config: TranslationConfig, reason: String): TranslationValidation {
        require(sentences.size == values.size)
        // Scan the whole bounded response: clipping it could hide a later duplicate ID.
        if (raw.length > 262144) return TranslationValidation(emptyList(), sentences.map { it to reason })
        val clean = raw.replace(Regex("<think>[\\s\\S]*?</think>"), "")
            .substringBefore("<think>").substringBefore("</think>").trim()
            .removePrefix("```json").removePrefix("```").trim()
        val rows = mutableListOf<Pair<String, String?>>()
        val prefix = Regex("^\\s*(?:\\{\\s*\"translations\"\\s*:\\s*)?\\[").find(clean)
        val rootRow = Regex("^\\{\\s*\"id\"\\s*:").containsMatchIn(clean)
        if (prefix != null || rootRow) {
            var cursor = prefix?.range?.last?.plus(1) ?: 0
            while (cursor < clean.length) {
                while (cursor < clean.length && (clean[cursor].isWhitespace() || clean[cursor] == ',')) cursor++
                if (cursor >= clean.length || clean[cursor] != '{') break
                val start = cursor
                var depth = 0; var quoted = false; var escaped = false
                do {
                    val ch = clean[cursor++]
                    if (quoted) {
                        if (escaped) escaped = false else if (ch == '\\') escaped = true else if (ch == '\"') quoted = false
                    } else when (ch) { '\"' -> quoted = true; '{' -> depth++; '}' -> depth-- }
                } while (cursor < clean.length && (depth > 0 || quoted))
                val row = clean.substring(start, cursor)
                if (depth == 0 && !quoted) {
                    runCatching {
                        val json = JSONObject(row)
                        val id = json.getString("id")
                        rows += id to (json.opt("translation") as? String)
                    }.onFailure { rows.clear() }
                    // If a complete row itself is malformed, its identity is not trustworthy.
                    if (rows.isEmpty()) break
                } else {
                    // Count identities even if the last row has no recoverable body yet.
                    // Nested/extra identities conservatively block an ambiguous partial row.
                    val jsonString = "\"(?:[^\"\\\\\\u0000-\\u001f]|\\\\(?:[\"\\\\/bfnrt]|u[0-9a-fA-F]{4}))*\""
                    val ids = Regex("($jsonString)\\s*:\\s*($jsonString)").findAll(row).mapNotNull { field ->
                        runCatching {
                            val entry = JSONObject("{${field.groupValues[1]}:${field.groupValues[2]}}")
                            if (entry.has("id")) entry.getString("id") else null
                        }.getOrNull()
                    }.toList()
                    val partialIndex = rows.size
                    rows += ids.map { it to null }
                    // A last row cut inside its string can retain complete JSON escapes only.
                    val pattern = Regex("^\\{\\s*\"id\"\\s*:\\s*($jsonString)\\s*,\\s*\"translation\"\\s*:\\s*\"((?:[^\"\\\\\\u0000-\\u001f]|\\\\(?:[\"\\\\/bfnrt]|u[0-9a-fA-F]{4}))*)")
                    pattern.find(row)?.let { match ->
                        val tail = row.substring(match.range.last + 1).trim()
                        val id = runCatching { JSONObject("{\"value\":${match.groupValues[1]}}").getString("value") }.getOrNull()
                        // No later property may change the identity of an unfinished row.
                        if (ids.size == 1 && id != null && ids.single() == id &&
                            (tail.isEmpty() || tail == "\"" || Regex("\\\\(?:u[0-9a-fA-F]{0,3})?").matches(tail))) runCatching {
                            var text = JSONObject("{\"value\":\"${match.groupValues[2]}\"}").getString("value")
                            if (text.lastOrNull()?.isHighSurrogate() == true) text = text.dropLast(1)
                            rows[partialIndex] = id to text
                        }
                    }
                    break
                }
            }
        } else if (sentences.size == 1 && clean.isNotBlank() && clean.first() !in "{[" && !clean.startsWith("```")) {
            // A text-only answer to a single owned sentence is still useful, with its format error.
            rows += "s1" to clean.removeSuffix("```").trim()
        }
        val grouped = rows.groupBy { it.first }
        val accepted = mutableListOf<Pair<SourceSentence, String>>()
        val rejected = mutableListOf<Pair<SourceSentence, String>>()
        sentences.forEachIndexed { i, sentence ->
            val candidates = grouped["s${i + 1}"]
            val text = candidates?.singleOrNull()?.second?.trim()
            if (!text.isNullOrBlank() && TranslationWarnings.body(text).isNotBlank())
                accepted += sentence to values[i].forDisplay(text, sentence.text, config, listOf(reason))
            else rejected += sentence to reason
        }
        return TranslationValidation(accepted, rejected)
    }
    private val protected = Regex("```[\\s\\S]*?```|`[^`]+`|\\$\\$[\\s\\S]*?\\$\\$|\\$[^$\\n]+\\$|https?://[^\\s<>]+|\\[[0-9][0-9,; \\u2013-]*]|(?<![\\p{L}])[-+]?\\d+(?:[.,]\\d+)*(?:%|\\s?(?:kg|mg|km|cm|mm|ms|Hz|GHz|MHz|MB|GB|K|°C|m/s))?")
    fun protect(text: String, firstToken: Int = 0): ProtectedText {
        require(!text.contains(Regex("__PR\\d+__"))) { "RESERVED_TOKEN_IN_SOURCE" }
        val tokens = mutableListOf<String>()
        return ProtectedText(protected.replace(text) { m -> "__PR${firstToken + tokens.size}__".also { tokens += m.value } }, tokens, firstToken)
    }
    fun prompt(sentences: List<SourceSentence>, c: TranslationConfig): Pair<ModelPrompt, List<ProtectedText>> {
        var nextToken = 0
        val values = sentences.map { protect(it.text, nextToken).also { nextToken += it.tokens.size } }
        val rows = JSONArray()
        values.forEachIndexed { i, p -> rows.put(JSONObject().put("id", "s${i + 1}").put("text", p.text)) }
        val terms = glossary(c)
        val system = if (c.targetLanguage.lowercase() in setOf("zh-cn", "zh-hans", "zh", "简体中文"))
            "将每个 text 的全文翻译为简体中文，包括所有句子。保留原意、否定和转折，不要总结。" +
            "只输出一个 JSON 对象，包含 translations 数组。每个数组元素包含 id 和 translation 两个字段；id 照抄输入，translation 填写实际译文。" +
            (if (values.any { it.tokens.isNotEmpty() }) "所有 __PR数字__ 标记必须逐字复制，不得增删或改写，包括两侧下划线。" else "") +
            "术语表：$terms"
        else "Translate every complete text from ${c.sourceLanguage} to ${c.targetLanguage}, including every sentence. Do not summarize. " +
            "Return only a JSON object containing a translations array. Each entry must have id and translation fields; keep each input id and fill translation with the actual translated text. " +
            (if (values.any { it.tokens.isNotEmpty() }) "Copy every __PRnumber__ marker from EACH input sentence exactly once, including both pairs of underscores. " else "") +
            "Preserve terms, code, equations, references, negation and contrasts. No reasoning. Glossary: $terms"
        return ModelPrompt(system, JSONObject().put("sentences", rows).toString() + "\n/no_think", sentences.size) to values
    }
    private fun glossary(c: TranslationConfig): JSONObject {
        val glossary = JSONObject(c.glossary)
        val terms = JSONObject()
        glossary.keys().forEach { k -> if (glossary.opt(k) is String) terms.put(k, glossary.getString(k)) }
        glossary.optJSONObject(c.targetLanguage)?.let { lang -> lang.keys().forEach { terms.put(it, lang.getString(it)) } }
        return terms
    }
    /** One known sentence, so the caller owns its ID and no model-generated JSON is needed. */
    fun textPrompt(sentence: SourceSentence, c: TranslationConfig): Pair<ModelPrompt, ProtectedText> {
        val protected = protect(sentence.text)
        fun language(code: String): String = when (code.lowercase()) {
            "en" -> "English"
            "zh-cn", "zh-hans", "zh", "简体中文" -> "Chinese"
            else -> code
        }
        val source = if (c.sourceLanguage == "auto") "the original language" else language(c.sourceLanguage)
        val target = language(c.targetLanguage)
        val required = protected.markers.joinToString(", ")
        val system = if (target == "Chinese")
            "将下文完整翻译为简体中文，只输出译文正文，不要 JSON、解释或代码围栏。保留所有句子的原意、否定和转折。" +
            (if (required.isNotEmpty()) "所有 __PR数字__ 标记必须逐字复制，不得增删或改写，包括两侧下划线。" else "") +
            "术语表：${glossary(c)}"
        else "Translate the complete text from $source into $target. Preserve every sentence, negation and contrast. " +
            "Output only the translated text, without JSON, commentary or a surrounding code fence. " +
            (if (target == "Chinese") "Use Simplified Chinese. " else "") +
            (if (required.isNotEmpty()) "Copy these markers exactly once each, including underscores: $required. " else "") +
            "Glossary: ${glossary(c)}"
        val user = protected.text + "\n/no_think"
        return ModelPrompt(system, user, outputFormat = ModelOutputFormat.TEXT) to protected
    }
    fun validateText(raw: String, protected: ProtectedText, c: TranslationConfig): String {
        require(raw.toByteArray().size <= 262144) { "OUTPUT_TOO_LARGE" }
        require(!raw.contains("<think>") && !raw.contains("</think>")) { "THINKING_OUTPUT" }
        val value = raw.trim()
        require(value.isNotEmpty()) { "EMPTY_TRANSLATION" }
        require(!value.startsWith("```") && !value.startsWith("{") && !value.startsWith("[")) { "UNEXPECTED_TEXT_WRAPPER" }
        return protected.forDisplay(value, protected.restore(protected.text), c)
    }
    fun validate(raw: String, sentences: List<SourceSentence>, protected: List<ProtectedText>, config: TranslationConfig = TranslationConfig()): List<Pair<SourceSentence, String>> {
        val result = validateEach(raw, sentences, protected, config)
        require(result.rejected.isEmpty()) { result.rejected.first().second }
        return result.accepted
    }
    /** Only complete JSON with unique, known IDs may contribute independently validated rows. */
    fun validateEach(raw: String, sentences: List<SourceSentence>, protected: List<ProtectedText>, config: TranslationConfig = TranslationConfig()): TranslationValidation {
        require(sentences.size == protected.size)
        require(raw.toByteArray().size <= 262144) { "OUTPUT_TOO_LARGE" }
        require(!raw.contains("<think>") && !raw.contains("</think>")) { "THINKING_OUTPUT" }
        val clean = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val parser = JSONTokener(clean)
        val root = parser.nextValue()
        require(parser.nextClean() == '\u0000') { "TRAILING_OUTPUT" }
        val array = when (root) {
            is JSONArray -> root
            is JSONObject -> root.getJSONArray("translations")
            else -> error("INVALID_RESULT_ROOT")
        }
        val outputs = mutableMapOf<String, Any>()
        for (i in 0 until array.length()) {
            val row = array.getJSONObject(i)
            val id = row.getString("id")
            require(id.matches(Regex("s[1-9][0-9]*")) && id.removePrefix("s").toInt() in 1..sentences.size) { "UNKNOWN_ID" }
            require(!outputs.containsKey(id)) { "DUPLICATE_ID" }
            outputs[id] = row.opt("translation") ?: JSONObject.NULL
        }
        val accepted = mutableListOf<Pair<SourceSentence, String>>()
        val rejected = mutableListOf<Pair<SourceSentence, String>>()
        sentences.forEachIndexed { i, s ->
            try {
                val value = outputs["s${i + 1}"] ?: error("RESULT_COUNT_MISMATCH")
                require(value is String) { "INVALID_TRANSLATION_TYPE" }
                require(value.isNotBlank()) { "EMPTY_TRANSLATION" }
                accepted += s to protected[i].forDisplay(value.trim(), s.text, config)
            } catch (e: Exception) { rejected += s to (e.message ?: "INVALID_TRANSLATION").take(512) }
        }
        return TranslationValidation(accepted, rejected)
    }
}
