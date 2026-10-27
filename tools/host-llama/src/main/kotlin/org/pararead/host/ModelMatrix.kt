package org.pararead.host

import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import org.json.JSONArray
import org.json.JSONObject
import org.pararead.core.*
import org.pararead.llama.LlamaCppProvider
import org.pararead.llama.NativeBridge
import java.io.File

/** Opt-in real weights, production JNI, prompt and validator. Raw failures are retained. */
suspend fun runModelMatrix(args: List<String>) {
    val output = File(args.firstOrNull() ?: "docs/reports/model-matrix.json")
    val threads = System.getenv("PARAREAD_CPU_THREADS")?.toInt() ?: 6
    val root = File("artifacts/models")
    val manifest = args.getOrNull(1)?.let { JSONArray(File(it).readText()) }
    val extended = manifest != null
    val smoke = args.getOrNull(2) == "smoke"
    require(args.size <= 2 || args.size == 3 && smoke) { "Historical prompt probes are reproducible at commit 0f36d5a" }
    val files = (manifest?.let { rows -> (0 until rows.length()).map { rows.getJSONObject(it).getString("file") } }
        ?: listOf("Qwen3-1.7B-Q4_K_M.gguf", "LFM2.5-1.2B-Instruct-Q4_K_M.gguf", "LFM2.5-350M-Q4_K_M.gguf", "Qwen3.5-0.8B-Q4_0.gguf"))
        .filter { System.getenv("PARAREAD_MATRIX_MODEL")?.let(it::contains) ?: true }
    val results = JSONArray()
    val report = JSONObject().put("date", java.time.LocalDate.now().toString()).put("platform", System.getProperty("os.name") + " " + System.getProperty("os.arch"))
        .put("physicalAndroidDevice", false).put("threads", threads).put("engine", NativeBridge.version()).put("systemInfo", NativeBridge.systemInfo())
        .put("context", 4096).put("batch", 512).put("ubatch", 128).put("gpuLayers", 0).put("models", results)
        .put("extendedFixtures", extended).put("diagnosticSmoke", smoke).put("formatPassCertifiesSemanticAccuracy", false).put("promptVariant", "production-v6-ascii").put("promptVersion", 6)
        .put("hardware", System.getenv("PARAREAD_HARDWARE") ?: "not recorded")
    fun save() { output.parentFile.mkdirs(); output.writeText(report.toString(2) + "\n") }
    val fixtures = linkedMapOf(
        "short" to listOf(SourceSentence("a", SpeedTest.SOURCE, "p1")),
        "long" to listOf(SourceSentence("a", SpeedTest.LONG_SOURCE, "p1")),
        "protected-batch" to listOf(
            SourceSentence("a", "The model processes 100 pages using 4 GB of memory [12].", "p1"),
            SourceSentence("b", "For ${'$'}E=mc^2${'$'}, retain the equation and see https://example.org/paper.", "p1"),
            SourceSentence("c", "The attention mechanism uses a key-value cache; the code is `cache.get(key)`.", "p1"),
            SourceSentence("d", "The proposed method reduces latency by 12.5% without changing the input order.", "p1")),
        "escaped-text" to listOf(SourceSentence("a", "The author calls this a \"reading assistant\".\nKeep the meaning of both sentences.", "p1")))
    if (extended) fixtures.putAll(linkedMapOf(
        "negation-and-hedging" to listOf(
            SourceSentence("a", "The increase was not statistically significant.", "p1"),
            SourceSentence("b", "The result may indicate an association, but it does not establish causation.", "p1"),
            SourceSentence("c", "We did not observe a reduction in memory use.", "p1")),
        "numeric-contrast" to listOf(
            SourceSentence("a", "Accuracy fell from 91.2% to 87.6%, whereas latency improved from 120 ms to 80 ms.", "p1"),
            SourceSentence("b", "Only 3 of the 50 documents failed; the other 47 were translated successfully.", "p1")),
        "technical-contrast" to listOf(
            SourceSentence("a", "The ablation study removes cross-attention while keeping the key-value cache unchanged.", "p1"),
            SourceSentence("b", "Unlike prior work, we evaluate on unseen documents rather than on the training set.", "p1")),
        "two-sentence-discourse" to listOf(SourceSentence("a", "The baseline uses more memory. However, the proposed method is slower on short inputs.", "p1"))))
    if (smoke) fixtures.keys.retainAll(setOf("short", "protected-batch", "negation-and-hedging"))
    val config = TranslationConfig(sourceLanguage = "en", targetLanguage = "zh-CN", glossary = "{\"key-value cache\":\"键值缓存\",\"attention mechanism\":\"注意力机制\"}")
    for (name in files) {
        val file = File(root, name); require(file.isFile) { file.path }
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input -> val bytes = ByteArray(262144); while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) } }
        val model = JSONObject().put("file", name).put("bytes", file.length()).put("sha256", digest.digest().joinToString("") { "%02x".format(it) }).put("cases", JSONArray())
        manifest?.let { rows -> (0 until rows.length()).map { rows.getJSONObject(it) }.first { it.getString("file") == name }.let { source ->
            require(source.getLong("bytes") == file.length() && source.getString("sha256") == model.getString("sha256")) { "MODEL_MANIFEST_MISMATCH: $name" }
            model.put("source", source)
        } }
        results.put(model); save()
        for (guided in listOf(true, false)) {
            if (smoke && !guided) continue
            val provider = LlamaCppProvider(file.path, threads, constrainJson = guided)
            try {
                for ((fixture, sentences) in fixtures) {
                    if (extended && !guided && fixture !in setOf("short", "protected-batch", "escaped-text")) continue
                    val entry = JSONObject().put("fixture", fixture).put("jsonGrammar", guided)
                    model.getJSONArray("cases").put(entry)
                    val start = System.nanoTime()
                    val streamed = StringBuilder()
                    try {
                        withTimeout(120000) {
                            val (original, protected) = StructuredTranslation.prompt(sentences, config)
                            val prompt = original
                            entry.put("input", prompt.user).put("system", prompt.system)
                            var raw: String? = null
                            provider.generate(prompt).collect { if (it is ProviderEvent.Complete) raw = it.text; if (it is ProviderEvent.Delta) streamed.append(it.text) }
                            entry.put("raw", raw)
                            provider.metrics?.let { m -> entry.put("promptTokens", m.promptTokens).put("generatedTokens", m.generatedTokens)
                                .put("tokensPerSecond", m.tokensPerSecond).put("samplingNanos", m.samplingNanos).put("decodeNanos", m.decodeNanos).put("callbackNanos", m.callbackNanos) }
                            val translated = StructuredTranslation.validate(checkNotNull(raw), sentences, protected)
                            check(translated.all { (_, value) -> value.any { it in '\u4e00'..'\u9fff' } }) { "NO_CHINESE_TRANSLATION" }
                            entry.put("translations", JSONArray(translated.map { (s, t) -> JSONObject().put("source", s.text).put("translation", t) }))
                            entry.put("status", "passed")
                        }
                    } catch (e: Exception) { entry.put("status", "failed").put("error", e.toString()).put("raw", streamed.toString()) }
                    entry.put("totalMs", (System.nanoTime() - start) / 1e6)
                    model.put("architecture", provider.architecture); save()
                    println("$name grammar=$guided $fixture: ${entry.optString("status")} ${entry.optString("error")} ${entry.optDouble("tokensPerSecond")}")
                    if (provider.architecture == "unloaded") { model.put("loadError", entry.optString("error")); save(); break }
                }
                if (model.has("loadError")) break
                if (guided && extended && !smoke) {
                    val samples = JSONArray(); model.put("longWarmSamples", samples)
                    repeat(3) {
                        val sample = JSONObject(); samples.put(sample)
                        try { withTimeout(120000) {
                            val (original, protected) = StructuredTranslation.prompt(fixtures.getValue("long"), config)
                            val prompt = original
                            var raw = ""; val started = System.nanoTime()
                            provider.generate(prompt).collect { if (it is ProviderEvent.Complete) raw = it.text }
                            sample.put("totalMs", (System.nanoTime() - started) / 1e6).put("raw", raw)
                            provider.metrics?.let { m -> sample.put("promptTokens", m.promptTokens).put("generatedTokens", m.generatedTokens)
                                .put("tokensPerSecond", m.tokensPerSecond).put("prefillNanos", m.prefillNanos).put("generationNanos", m.generationNanos) }
                            val translated = StructuredTranslation.validate(raw, fixtures.getValue("long"), protected)
                            check(translated.all { (_, value) -> value.any { it in '\u4e00'..'\u9fff' } }) { "NO_CHINESE_TRANSLATION" }
                            sample.put("status", "passed")
                        } } catch (e: Exception) { sample.put("status", "failed").put("error", e.toString()) }
                        save()
                    }
                }
                if (guided && !smoke) {
                    val queueResult = JSONObject(); model.put("productionQueue", queueResult)
                    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                    val attempts = JSONArray(); queueResult.put("attempts", attempts)
                    val committed = java.util.concurrent.ConcurrentHashMap<String, String>()
                    val wrapped = object : TranslationProvider {
                        override fun generate(prompt: ModelPrompt) = flow {
                            val wire = prompt
                            provider.generate(wire).collect { event ->
                                if (event is ProviderEvent.Complete) {
                                    attempts.put(JSONObject().put("input", wire.user).put("system", wire.system).put("raw", event.text))
                                    emit(event)
                                } else emit(event)
                            }
                        }
                    }
                    val store = object : TranslationCache {
                        override suspend fun get(key: String): String? = null
                        override suspend fun put(key: String, text: String, translation: String, config: TranslationConfig) {}
                    }
                    try {
                        withTimeout(120000) {
                            val done = CompletableDeferred<Unit>()
                            TranslationQueue(scope, store) { wrapped }.submit(TranslationWork("matrix", 0, 0, fixtures.getValue("protected-batch"), config,
                                committed = { s, text, _ -> committed[s.id] = text },
                                finished = { failures, cancelled ->
                                    queueResult.put("failures", JSONArray(failures.map { JSONObject().put("id", it.first).put("reason", it.second) }))
                                        .put("cancelled", cancelled).put("status", if (failures.isEmpty() && !cancelled && committed.size == 4) "passed" else "failed")
                                    done.complete(Unit)
                                }))
                            done.await()
                            queueResult.put("translations", JSONObject(committed.toMap()))
                        }
                    } catch (e: Exception) { queueResult.put("status", "failed").put("error", e.toString()) }
                    finally { scope.cancel() }
                    save()
                    val engine = JSONArray(); model.put("engineSamples", engine)
                    repeat(3) {
                        try { withTimeout(120000) {
                            val v = provider.benchmark()
                            engine.put(JSONObject().put("pp512", v[0]).put("tg128", v[1]).put("prefillMs", v[2]).put("decodeMs", v[3]))
                        } } catch (e: Exception) { model.put("benchmarkError", e.toString()) }
                        save()
                    }
                }
            } finally { provider.closeAndJoin() }
        }
    }
    save()
}
