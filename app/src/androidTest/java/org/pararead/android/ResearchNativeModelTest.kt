package org.pararead.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.pararead.core.*
import org.pararead.llama.*
import org.pararead.probes.*
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Opt-in research provider; its prompt adapter is excluded from app main and release APKs. */
@RunWith(AndroidJUnit4::class)
class ResearchNativeModelTest {
    @Test fun sentenceAdapterUsesRealQueueAndVerifiedWeights(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val path = args.getString("modelPath"); val specPath = args.getString("modelSpecPath")
        assumeTrue("Supply modelPath and modelSpecPath", path != null && specPath != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val source = JSONObject(File(specPath!!).readText()); val file = File(path!!)
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input -> val bytes = ByteArray(262144); while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) } }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        check(hash == source.getString("sha256") && file.length() == source.getLong("bytes")) { "MODEL_MANIFEST_MISMATCH" }
        val native = LlamaCppProvider(path, 6, fullPrecisionKv = source.optBoolean("fullPrecisionKv"),
            greedy = source.optBoolean("greedy"), rawCompletion = source.optBoolean("rawCompletion"))
        val cases = JSONArray()
        val report = JSONObject().put("source", source).put("cases", cases).put("engine", NativeBridge.version())
            .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}").put("threads", 6)
            .put("physicalAndroidDevice", android.os.Build.HARDWARE !in setOf("ranchu", "goldfish"))
            .put("adapterInReleasedApp", false).put("formatPassCertifiesSemanticAccuracy", false)
        val output = File(context.filesDir, "model-research.json")
        fun save() { output.writeText(report.toString(2)) }
        val fixtures = queueProbeFixtures().also { if (args.getString("includeHeldout") == "true") it.putAll(heldOutProbeFixtures()) }
        try {
            for ((name, sentences) in fixtures) {
                val entry = JSONObject().put("fixture", name).put("sources", JSONArray(sentences.map { it.text }))
                cases.put(entry)
                val attempts = JSONArray(); entry.put("attempts", attempts)
                val values = ConcurrentHashMap<String, String>(); val commits = JSONArray(); entry.put("commits", commits)
                var calls = 0
                val wrapped = object : TranslationProvider {
                    override fun generate(prompt: ModelPrompt) = flow {
                        calls++
                        val attempt = JSONObject().put("format", prompt.outputFormat.name).put("system", prompt.system).put("input", prompt.user)
                        attempts.put(attempt); val real = JSONArray(); attempt.put("nativeCalls", real)
                        val provider = if (source.optBoolean("researchAdapter", true)) ResearchSentenceProvider(native, source) { real.put(it) } else native
                        provider.generate(prompt).collect { event ->
                            if (event is ProviderEvent.Complete) attempt.put("raw", event.text)
                            emit(event)
                        }
                    }
                }
                val cache = object : TranslationCache {
                    override suspend fun get(key: String) = values[key]
                    override suspend fun put(key: String, text: String, translation: String, config: TranslationConfig) { values[key] = translation }
                }
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                val started = System.nanoTime()
                try { withTimeout(180000) {
                    val config = TranslationConfig(modelHash = hash, sourceLanguage = "en", targetLanguage = "zh-CN")
                    val queue = TranslationQueue(scope, cache) { wrapped }; val done = CompletableDeferred<Unit>()
                    queue.submit(TranslationWork(name, 0, 0, sentences, config,
                        committed = { s, text, _ -> commits.put(JSONObject().put("id", s.id).put("translation", text)) },
                        finished = { failures, cancelled ->
                            entry.put("status", if (!cancelled && failures.isEmpty() && commits.length() == sentences.size &&
                                (0 until commits.length()).all { commits.getJSONObject(it).getString("translation").any { ch -> ch in '\u4e00'..'\u9fff' } }) "passed" else "failed")
                                .put("failures", JSONArray(failures.map { JSONObject().put("id", it.first).put("reason", it.second) }))
                            done.complete(Unit)
                        }))
                    done.await(); val before = calls; val reopened = CompletableDeferred<Unit>()
                    queue.submit(TranslationWork("$name-reopen", 0, 0, sentences.filter { s -> (0 until commits.length()).any { commits.getJSONObject(it).getString("id") == s.id } }, config,
                        committed = { _, _, _ -> }, finished = { failures, cancelled -> check(failures.isEmpty() && !cancelled); reopened.complete(Unit) }))
                    reopened.await(); check(calls == before); entry.put("cacheReopenCalls", calls - before)
                } } catch (e: Exception) { entry.put("status", "failed").put("error", e.toString()) }
                finally { scope.cancel() }
                entry.put("providerCalls", calls).put("totalMs", (System.nanoTime() - started) / 1e6); save()
            }
            if (args.getString("benchmark") == "true") {
                val samples = JSONArray(); report.put("engineSamples", samples)
                repeat(3) { val v = native.benchmark(); samples.put(JSONObject().put("pp512", v[0]).put("tg128", v[1])) }
            }
            report.put("status", if ((0 until cases.length()).all { cases.getJSONObject(it).getString("status") == "passed" }) "passed" else "failed")
        } finally { native.closeAndJoin(); save() }
        assertEquals(report.toString(), "passed", report.getString("status"))
    }
}
