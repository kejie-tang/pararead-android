package org.pararead.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.pararead.core.*
import org.pararead.llama.LlamaCppProvider
import org.pararead.llama.NativeBridge
import java.io.File
import java.security.MessageDigest

/** Opt-in: install the test APK, import a GGUF, then pass -e ggufPath <app-private-path>. */
@RunWith(AndroidJUnit4::class)
class RealModelIntegrationTest {
    @Test fun ggufTranslationStreamingCancellationAndReuse(): Unit = runBlocking {
        val path = InstrumentationRegistry.getArguments().getString("ggufPath")
        assumeTrue("Supply ggufPath for the explicit real-model test", path != null)
        val model = File(path!!); assertTrue(model.isFile)
        val digest = MessageDigest.getInstance("SHA-256")
        model.inputStream().use { input ->
            val bytes = ByteArray(262144)
            while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
        }
        val report = JSONObject().put("environment", "Android API ${android.os.Build.VERSION.SDK_INT} ${android.os.Build.SUPPORTED_ABIS.joinToString()}")
            .put("device", android.os.Build.MODEL).put("hardware", android.os.Build.HARDWARE)
            .put("airplaneMode", android.provider.Settings.Global.getInt(InstrumentationRegistry.getInstrumentation().targetContext.contentResolver, android.provider.Settings.Global.AIRPLANE_MODE_ON, 0) == 1)
            .put("engine", NativeBridge.version()).put("modelBytes", model.length())
            .put("modelSha256", digest.digest().joinToString("") { "%02x".format(it) })
        val config = TranslationConfig(modelHash = report.getString("modelSha256"), sourceLanguage = "en", targetLanguage = "zh-CN")
        val sentences = listOf(
            SourceSentence("p1-b1-s1", "The model uses 4 GB of memory [12].", "p1-b1"),
            SourceSentence("p1-b1-s2", "Preserve ${'$'}E=mc^2${'$'} and the code `cache.get(key)`.", "p1-b1"),
        )
        val requestedThreads = InstrumentationRegistry.getArguments().getString("cpuThreads")?.toInt() ?: 0
        val provider = LlamaCppProvider(model.path, requestedThreads)
        report.put("requestedThreads", requestedThreads).put("effectiveThreads", provider.threadCount).put("availableProcessors", CpuThreads.maximum())
        suspend fun infer(label: String) {
            val attempts = JSONArray(); val entry = JSONObject().put("attempts", attempts); report.put(label, entry)
            var chunks = 0; var first = 0L
            val start = System.nanoTime()
            val counted = object : TranslationProvider {
                override fun generate(prompt: ModelPrompt) = flow {
                    val attempt = JSONObject().put("input", prompt.user); attempts.put(attempt)
                    val streamed = StringBuilder()
                    provider.generate(prompt).collect { event ->
                        when (event) {
                            ProviderEvent.Restart -> { first = 0L; chunks = 0; streamed.clear() }
                            is ProviderEvent.Delta -> { if (first == 0L) first = System.nanoTime(); chunks++; streamed.append(event.text) }
                            is ProviderEvent.Complete -> { attempt.put("raw", event.text); assertEquals(streamed.toString(), event.text) }
                        }
                        emit(event)
                    }
                }
            }
            val cache = java.util.concurrent.ConcurrentHashMap<String, String>()
            val values = java.util.concurrent.ConcurrentHashMap<String, String>()
            val store = object : TranslationCache {
                override suspend fun get(key: String) = cache[key]
                override suspend fun put(key: String, text: String, translation: String, config: TranslationConfig) { cache[key] = translation }
            }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val done = CompletableDeferred<Unit>()
                val queue = TranslationQueue(scope, store) { counted }
                check(queue.submit(TranslationWork(label, 0, 0, sentences, config,
                    committed = { source, value, _ -> values[source.id] = value },
                    finished = { failures, cancelled ->
                        if (failures.isEmpty() && !cancelled) done.complete(Unit)
                        else done.completeExceptionally(IllegalStateException("$failures cancelled=$cancelled"))
                    })))
                withTimeout(180000) { done.await() }
                assertEquals(sentences.map { it.id }.toSet(), values.keys)
                assertTrue(values.values.all { value -> value.any { it in '\u4e00'..'\u9fff' } })
                entry.put("firstDeltaMs", (first - start) / 1e6).put("totalMs", (System.nanoTime() - start) / 1e6)
                    .put("deltaEvents", chunks).put("providerCalls", attempts.length()).put("cacheEntries", cache.size)
                    .put("translations", JSONArray(sentences.map { s -> JSONObject().put("id", s.id).put("source", s.text).put("translation", values[s.id]) }))
            } finally { scope.cancel() }
        }
        try {
            infer("coldBatch")
            val first = CompletableDeferred<Unit>(); var completed = false
            val generation = launch(Dispatchers.Default) {
                provider.generate(StructuredTranslation.prompt(sentences, config).first).collect { event ->
                    if (event is ProviderEvent.Delta && event.text.isNotEmpty()) { first.complete(Unit); delay(5000) }
                    if (event is ProviderEvent.Complete) completed = true
                }
            }
            try {
                withTimeout(30000) { first.await() }
                val start = System.nanoTime(); generation.cancelAndJoin()
                report.put("cancelAndJoinMs", (System.nanoTime() - start) / 1e6)
                assertFalse(completed)
            } finally { generation.cancelAndJoin() }
            infer("afterCancelBatch")
            report.put("status", "passed")
        } catch (e: Throwable) {
            report.put("status", "failed").put("error", e.toString()); throw e
        } finally {
            provider.close()
            File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "real-model-android.json").writeText(report.toString(2) + "\n")
        }
    }
}
