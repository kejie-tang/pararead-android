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
        "prompt=6", "validator=5", "template=model-chat-v2", "nativeGrammar=exact-translation-json-v3").joinToString("\u0000")
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
    data class Delta(val text: String) : ProviderEvent
    data class Complete(val text: String) : ProviderEvent
}
data class ModelPrompt(val system: String, val user: String, val expectedResults: Int = 0)
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
    fun restore(value: String): String {
        val placeholders = Regex("__PR(\\d+)__").findAll(value).map { it.groupValues[1].toInt() }.toList()
        require(placeholders.sorted() == tokens.indices.map { it + firstToken }) { "PROTECTED_TOKEN_MISMATCH" }
        var restored = value
        tokens.forEachIndexed { i, t -> restored = restored.replace("__PR${i + firstToken}__", t) }
        return restored
    }
}
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
    fun drafts(raw: String, sentences: List<SourceSentence>, values: List<ProtectedText>): List<Pair<SourceSentence, String>> {
        val pattern = Regex("\"id\"\\s*:\\s*\"s([0-9]+)\"\\s*,\\s*\"translation\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)")
        return pattern.findAll(raw).mapNotNull { match ->
            val index = match.groupValues[1].toIntOrNull()?.minus(1) ?: return@mapNotNull null
            if (index !in sentences.indices) return@mapNotNull null
            runCatching {
                var text = JSONObject("{\"value\":\"${match.groupValues[2]}\"}").getString("value")
                values[index].tokens.forEachIndexed { i, token -> text = text.replace("__PR${i + values[index].firstToken}__", token) }
                sentences[index] to text
            }.getOrNull()
        }.toList()
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
        val glossary = JSONObject(c.glossary)
        val terms = JSONObject()
        glossary.keys().forEach { k -> if (glossary.opt(k) is String) terms.put(k, glossary.getString(k)) }
        glossary.optJSONObject(c.targetLanguage)?.let { lang -> lang.keys().forEach { terms.put(it, lang.getString(it)) } }
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
    fun validate(raw: String, sentences: List<SourceSentence>, protected: List<ProtectedText>): List<Pair<SourceSentence, String>> {
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
        require(array.length() == sentences.size) { "RESULT_COUNT_MISMATCH" }
        val outputs = mutableMapOf<String, String>()
        for (i in 0 until array.length()) {
            val row = array.getJSONObject(i)
            val id = row.getString("id")
            require(id.matches(Regex("s[1-9][0-9]*")) && id.removePrefix("s").toInt() in 1..sentences.size) { "UNKNOWN_ID" }
            require(!outputs.containsKey(id)) { "DUPLICATE_ID" }
            outputs[id] = row.getString("translation").trim().also { require(it.isNotEmpty()) { "EMPTY_TRANSLATION" } }
        }
        return sentences.mapIndexed { i, s -> s to protected[i].restore(outputs.getValue("s${i + 1}")) }
    }
}
