package org.pararead.core

import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** Native token counts include the JSON envelope, and exclude the end-of-generation token. */
data class GenerationMetrics(val promptTokens: Long, val generatedTokens: Long, val prefillNanos: Long, val generationNanos: Long) {
    init { require(promptTokens > 0 && generatedTokens > 0 && prefillNanos > 0 && generationNanos > 0) }
    val tokensPerSecond get() = generatedTokens * 1e9 / generationNanos
}

interface MeasuredTranslationProvider : TranslationProvider {
    val metrics: GenerationMetrics?
}

data class SpeedSample(val firstResponseMs: Double, val totalMs: Double, val metrics: GenerationMetrics, val translation: String) {
    init { require(firstResponseMs > 0 && totalMs >= firstResponseMs && totalMs.isFinite() && translation.isNotBlank()) }
    fun toJson() = JSONObject().put("firstResponseMs", firstResponseMs).put("totalMs", totalMs)
        .put("promptTokens", metrics.promptTokens).put("generatedTokens", metrics.generatedTokens)
        .put("prefillNanos", metrics.prefillNanos).put("generationNanos", metrics.generationNanos)
        .put("tokensPerSecond", metrics.tokensPerSecond).put("translation", translation)
    companion object {
        fun fromJson(j: JSONObject) = SpeedSample(j.getDouble("firstResponseMs"), j.getDouble("totalMs"),
            GenerationMetrics(j.getLong("promptTokens"), j.getLong("generatedTokens"), j.getLong("prefillNanos"), j.getLong("generationNanos")), j.getString("translation"))
    }
}

data class SpeedTestResult(val samples: List<SpeedSample>) {
    init { require(samples.size == 4) }
    val first get() = samples.first()
    fun warmMedian(value: (SpeedSample) -> Double): Double = samples.drop(1).map(value).sorted()[1]
    fun toJson(): JSONObject = JSONObject().put("methodVersion", 1).put("source", SpeedTest.SOURCE)
        .put("sourceLanguage", "en").put("targetLanguage", "zh-CN").put("translationCache", false)
        .put("samples", JSONArray(samples.map { it.toJson() }))
    companion object {
        fun fromJson(j: JSONObject): SpeedTestResult {
            require(j.getInt("methodVersion") == 1 && j.getString("source") == SpeedTest.SOURCE)
            val rows = j.getJSONArray("samples")
            return SpeedTestResult((0 until rows.length()).map { SpeedSample.fromJson(rows.getJSONObject(it)) })
        }
    }
}

object SpeedTest {
    const val SOURCE = "We propose a memory-efficient method for translating scientific documents."
    /** A fresh provider is required. Calls bypass the document queue and translation cache. */
    suspend fun run(provider: MeasuredTranslationProvider, progress: (Int) -> Unit = {}): SpeedTestResult {
        val sentences = listOf(SourceSentence("speed-test", SOURCE, "speed-test"))
        val (prompt, protected) = StructuredTranslation.prompt(sentences, TranslationConfig(sourceLanguage = "en", targetLanguage = "zh-CN"))
        val samples = (1..4).map { index ->
            progress(index)
            withTimeout(120_000) {
                val start = System.nanoTime()
                var first = 0L
                var complete: String? = null
                provider.generate(prompt).collect { event ->
                    when (event) {
                        is ProviderEvent.Delta -> if (first == 0L && event.text.isNotEmpty()) first = System.nanoTime()
                        is ProviderEvent.Complete -> { check(complete == null); complete = event.text }
                    }
                }
                val end = System.nanoTime()
                check(first > start) { "NO_STREAMED_RESPONSE" }
                val translation = StructuredTranslation.validate(checkNotNull(complete) { "INCOMPLETE_RESULT" }, sentences, protected).single().second
                check(translation.any { it in '\u4e00'..'\u9fff' }) { "EXPECTED_CHINESE_TRANSLATION" }
                SpeedSample((first - start) / 1e6, (end - start) / 1e6,
                    checkNotNull(provider.metrics) { "MISSING_NATIVE_TOKEN_METRICS" }, translation)
            }
        }
        return SpeedTestResult(samples)
    }
}
