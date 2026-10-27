package org.pararead.core

import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject

/** Native token counts include the JSON envelope, and exclude the end-of-generation token. */
data class GenerationMetrics(val promptTokens: Long, val generatedTokens: Long, val prefillNanos: Long, val generationNanos: Long,
    val samplingNanos: Long = 0, val decodeNanos: Long = 0, val callbackNanos: Long = 0) {
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
        .put("samplingNanos", metrics.samplingNanos).put("decodeNanos", metrics.decodeNanos).put("callbackNanos", metrics.callbackNanos)
        .put("tokensPerSecond", metrics.tokensPerSecond).put("translation", translation)
    companion object {
        fun fromJson(j: JSONObject) = SpeedSample(j.getDouble("firstResponseMs"), j.getDouble("totalMs"),
            GenerationMetrics(j.getLong("promptTokens"), j.getLong("generatedTokens"), j.getLong("prefillNanos"), j.getLong("generationNanos"),
                j.optLong("samplingNanos"), j.optLong("decodeNanos"), j.optLong("callbackNanos")), j.getString("translation"))
    }
}

data class SpeedTestResult(val samples: List<SpeedSample>, val source: String = SpeedTest.SOURCE) {
    init { require(samples.size == 4) }
    val first get() = samples.first()
    fun warmMedian(value: (SpeedSample) -> Double): Double = samples.drop(1).map(value).sorted()[1]
    fun toJson(): JSONObject = JSONObject().put("methodVersion", 2).put("source", source)
        .put("sourceLanguage", "en").put("targetLanguage", "zh-CN").put("translationCache", false)
        .put("samples", JSONArray(samples.map { it.toJson() }))
    companion object {
        fun fromJson(j: JSONObject): SpeedTestResult {
            require(j.getInt("methodVersion") in 1..2 && j.getString("source") in setOf(SpeedTest.SOURCE, SpeedTest.LONG_SOURCE))
            val rows = j.getJSONArray("samples")
            return SpeedTestResult((0 until rows.length()).map { SpeedSample.fromJson(rows.getJSONObject(it)) }, j.getString("source"))
        }
    }
}

object SpeedTest {
    const val SOURCE = "We propose a memory-efficient method for translating scientific documents."
    const val LONG_SOURCE = "Scientific documents combine continuous prose with equations, tables, references, and technical terminology. " +
        "A useful reading assistant should translate the prose while preserving the original meaning and the structure of the document. " +
        "Researchers often read the same paragraph several times to understand an unfamiliar method and compare it with earlier work. " +
        "Local language models can support this process without sending the document to a remote service. " +
        "Their practical speed depends on memory bandwidth, processor instructions, worker threads, and the amount of context being processed. " +
        "A short answer may finish before the processor reaches a steady operating state, so repeated measurements with longer outputs are useful. " +
        "Prompt processing and token generation should be measured separately because they perform different kinds of computation. " +
        "A fair comparison also records the exact model file, inference settings, and device conditions. " +
        "The goal of this experiment is to produce a faithful Chinese translation and retain enough timing information to distinguish engine performance from application overhead."
    /** A fresh provider is required. Calls bypass the document queue and translation cache. */
    suspend fun run(provider: MeasuredTranslationProvider, source: String = LONG_SOURCE, progress: (Int) -> Unit = {}): SpeedTestResult {
        val sentences = listOf(SourceSentence("speed-test", source, "speed-test"))
        val (prompt, protected) = StructuredTranslation.prompt(sentences, TranslationConfig(sourceLanguage = "en", targetLanguage = "zh-CN"))
        val samples = (1..4).map { index ->
            progress(index)
            withTimeout(120_000) {
                val start = System.nanoTime()
                var first = 0L
                var complete: String? = null
                provider.generate(prompt).collect { event ->
                    when (event) {
                        ProviderEvent.Restart -> { first = 0L; complete = null }
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
        return SpeedTestResult(samples, source)
    }
}
