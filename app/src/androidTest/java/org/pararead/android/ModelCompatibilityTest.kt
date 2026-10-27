package org.pararead.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.pararead.core.*
import org.pararead.llama.*
import java.io.File

/** Opt-in Android CPU/JNI smoke matrix. Structural failures remain in the report. */
@RunWith(AndroidJUnit4::class)
class ModelCompatibilityTest {
    @Test fun realWeightsUseProductionPromptAndValidator(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val path = args.getString("modelPath")
        assumeTrue("Supply modelPath", path != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = LlamaCppProvider(path!!, 6)
        val cases = JSONArray()
        val report = JSONObject().put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            .put("model", args.getString("modelLabel")).put("threads", 6).put("cases", cases).put("systemInfo", NativeBridge.systemInfo())
            .put("appVersion", BuildConfig.VERSION_NAME).put("physicalAndroidDevice", android.os.Build.HARDWARE !in setOf("ranchu", "goldfish"))
        val file = File(path)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input -> val bytes = ByteArray(262144); while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) } }
        report.put("bytes", file.length()).put("sha256", digest.digest().joinToString("") { "%02x".format(it) })
        val fixtures = linkedMapOf(
            "short" to listOf(SourceSentence("a", SpeedTest.SOURCE, "p1")),
            "protected-batch" to listOf(SourceSentence("a", "The model processes 100 pages using 4 GB of memory [12].", "p1"),
                SourceSentence("b", "For ${'$'}E=mc^2${'$'}, retain the equation and see https://example.org/paper.", "p1")),
            "negation" to listOf(SourceSentence("a", "The increase was not statistically significant.", "p1"),
                SourceSentence("b", "The result may indicate an association, but it does not establish causation.", "p1")),
            "escaped-text" to listOf(SourceSentence("a", "The author calls this a \"reading assistant\".\nKeep the meaning of both sentences.", "p1")))
        try {
            for ((name, sentences) in fixtures) {
                val entry = JSONObject().put("fixture", name); cases.put(entry)
                val raw = StringBuilder()
                try { withTimeout(120000) {
                    val (prompt, protected) = StructuredTranslation.prompt(sentences, TranslationConfig(sourceLanguage = "en", targetLanguage = "zh-CN"))
                    provider.generate(prompt).collect { if (it is ProviderEvent.Delta) raw.append(it.text) }
                    val translations = StructuredTranslation.validate(raw.toString(), sentences, protected)
                    check(translations.all { (_, value) -> value.any { it in '\u4e00'..'\u9fff' } })
                    entry.put("status", "passed").put("translations", JSONArray(translations.map { (s, t) -> JSONObject().put("source", s.text).put("translation", t) }))
                } } catch (e: Exception) { entry.put("status", "failed").put("error", e.toString()) }
                entry.put("raw", raw.toString())
                provider.metrics?.let { entry.put("generatedTokens", it.generatedTokens).put("tokensPerSecond", it.tokensPerSecond) }
            }
            report.put("architecture", provider.architecture)
            val queueResult = JSONObject(); report.put("productionQueue", queueResult)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val committed = java.util.concurrent.ConcurrentHashMap<String, String>()
            val saved = java.util.concurrent.ConcurrentHashMap<String, String>()
            val commitOrder = JSONArray(); queueResult.put("commits", commitOrder)
            val attempts = JSONArray(); queueResult.put("attempts", attempts)
            val replayTextRecovery = args.getString("replayFailureForText") == "true"
            queueResult.put("replayedFailuresForTextRecovery", replayTextRecovery)
            val wrapped = object : TranslationProvider {
                override fun generate(prompt: ModelPrompt) = flow {
                    // Fault injection checks the real native TEXT route even when first-pass JSON succeeds.
                    if (replayTextRecovery && attempts.length() < 2) {
                        val raw = if (attempts.length() == 0)
                            """{"translations":[{"id":"s1","translation":"模型使用了 __PR0__ 页，其中 __PR1__ 页。"},{"id":"s2","translation":"对于 __PR3__，保留方程并查看 __PR4__"}]}"""
                        else """{"translations":[{"id":"s1","translation":"模型使用了 __PR0__ 页，其中 __PR1__ 页。"}]}"""
                        attempts.put(JSONObject().put("format", prompt.outputFormat.name).put("input", prompt.user).put("raw", raw).put("replayedFailure", true))
                        emit(ProviderEvent.Complete(raw)); return@flow
                    }
                    provider.generate(prompt).collect { event ->
                        if (event is ProviderEvent.Complete) attempts.put(JSONObject().put("format", prompt.outputFormat.name)
                            .put("system", prompt.system).put("input", prompt.user).put("raw", event.text))
                        emit(event)
                    }
                }
            }
            val cache = object : TranslationCache {
                override suspend fun get(key: String) = saved[key]
                override suspend fun put(key: String, text: String, translation: String, config: TranslationConfig) { saved[key] = translation }
            }
            try { withTimeout(120000) {
                val done = CompletableDeferred<Unit>()
                val queue = TranslationQueue(scope, cache) { wrapped }
                queue.submit(TranslationWork("android-model", 0, 0,
                    fixtures.getValue("protected-batch"), TranslationConfig(sourceLanguage = "en", targetLanguage = "zh-CN"),
                    committed = { s, t, _ -> committed[s.id] = t; commitOrder.put(s.id) }, finished = { failures, cancelled ->
                        queueResult.put("status", if (failures.isEmpty() && !cancelled && committed.size == 2 && committed.values.all { t -> t.any { it in '\u4e00'..'\u9fff' } }) "passed" else "failed")
                            .put("failures", JSONArray(failures.map { JSONObject().put("id", it.first).put("reason", it.second) }))
                        done.complete(Unit)
                    }))
                done.await(); queueResult.put("translations", JSONObject(committed.toMap()))
                val before = attempts.length(); val reopened = CompletableDeferred<Unit>()
                queue.submit(TranslationWork("android-reopen", 0, 0, fixtures.getValue("protected-batch").filter { committed.containsKey(it.id) },
                    TranslationConfig(sourceLanguage = "en", targetLanguage = "zh-CN"),
                    committed = { _, _, _ -> }, finished = { failures, cancelled ->
                        check(failures.isEmpty() && !cancelled); reopened.complete(Unit)
                    }))
                reopened.await(); queueResult.put("cacheReopenCalls", attempts.length() - before)
                check(attempts.length() == before) { "CACHED_SENTENCE_RETRANSLATED" }
            } } catch (e: Exception) { queueResult.put("status", "failed").put("error", e.toString()) }
            finally { scope.cancel() }
            if (args.getString("benchmark") == "true") {
                provider.closeAndJoin()
                val measured = LlamaCppProvider(path, 6, singleResult = true)
                try {
                    report.put("speedTest", SpeedTest.run(measured).toJson())
                    val samples = JSONArray(); report.put("engineSamples", samples)
                    repeat(3) {
                        val v = measured.benchmark()
                        samples.put(JSONObject().put("pp512", v[0]).put("tg128", v[1]))
                    }
                } catch (e: Exception) { report.put("speedError", e.toString()) }
                finally { measured.closeAndJoin() }
            }
        } finally {
            provider.closeAndJoin()
            File(context.filesDir, "model-compatibility.json").writeText(report.toString(2))
        }
        assertEquals(report.toString(), "passed", cases.getJSONObject(0).getString("status"))
        if (args.getString("requireAll") == "true") for (i in 0 until cases.length()) {
            assertEquals(report.toString(), "passed", cases.getJSONObject(i).getString("status"))
        }
        assertEquals(report.toString(), "passed", report.getJSONObject("productionQueue").getString("status"))
    }
}
