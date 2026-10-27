package org.pararead.host

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONArray
import org.json.JSONObject
import org.pararead.core.*
import org.pararead.llama.LlamaCppProvider
import org.pararead.llama.NativeBridge
import okhttp3.OkHttpClient
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Explicit real-model integration check. Not part of the normal unit test suite. */
fun main(args: Array<String>) = runBlocking {
    val mode = args.getOrNull(0) ?: "jni"
    if (mode == "matrix") { runModelMatrix(args.drop(1)); return@runBlocking }
    if (mode == "plain") { runPlainModelProbe(args.drop(1)); return@runBlocking }
    if (mode == "recovery") { runRecoveryCheck(args.drop(1)); return@runBlocking }
    require(mode in setOf("jni", "api")) { "Usage: run --args='jni|api [report.json]'" }
    val file = File(requireNotNull(System.getenv("PARAREAD_GGUF")) { "Set PARAREAD_GGUF to a local GGUF file" })
    require(file.isFile)
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(262144)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
    }
    val hash = digest.digest().joinToString("") { "%02x".format(it) }
    val endpoint = System.getenv("PARAREAD_LLAMA_URL") ?: "http://127.0.0.1:18080/v1"
    val config = TranslationConfig(provider = mode, model = if (mode == "jni") file.name else "qwen3:1.7b", modelHash = hash,
        sourceLanguage = "en", targetLanguage = "zh-CN", batchSize = 4,
        glossary = "{\"key-value cache\":\"键值缓存\",\"attention mechanism\":\"注意力机制\"}")
    val cases = JSONArray()
    val report = JSONObject().put("date", java.time.LocalDate.now(java.time.ZoneId.of("Asia/Shanghai")).toString()).put("platform", System.getProperty("os.name") + " " + System.getProperty("os.arch"))
        .put("physicalAndroidDevice", false).put("provider", mode).put("modelTag", config.model).put("modelBytes", file.length())
        .put("modelSha256", hash).put("cases", cases)
    val requestedThreads = System.getenv("PARAREAD_CPU_THREADS")?.toInt() ?: 4
    val httpClient = OkHttpClient.Builder().callTimeout(120, java.util.concurrent.TimeUnit.SECONDS).readTimeout(60, java.util.concurrent.TimeUnit.SECONDS).build()
    val provider: TranslationProvider = if (mode == "jni") LlamaCppProvider(file.path, requestedThreads) else OpenAICompatibleProvider(endpoint, config.model, client = httpClient)
    if (provider is LlamaCppProvider) report.put("engine", NativeBridge.version()).put("requestedThreads", requestedThreads).put("effectiveThreads", provider.threadCount)
        .put("configuration", "CPU; ${provider.threadCount} threads; ctx4096; batch512; ubatch128; no Metal/BLAS/Accelerate")
    else report.put("endpoint", endpoint)
    val outputFile = File(args.getOrNull(1) ?: System.getenv("PARAREAD_CHECK_REPORT") ?: "docs/reports/real-model-$mode.json")
    suspend fun runCase(name: String, block: suspend (JSONObject) -> Unit) {
        val entry = JSONObject().put("name", name); cases.put(entry)
        val start = System.nanoTime()
        try { withTimeout(180000) { block(entry) }; entry.put("status", "passed") }
        catch (e: Exception) { entry.put("status", "failed").put("error", e.toString()) }
        entry.put("totalMs", (System.nanoTime() - start) / 1e6)
        println("$name: ${entry.optString("status")} ${entry.optString("error")}")
    }
    suspend fun infer(sentences: List<SourceSentence>, entry: JSONObject) {
        val (prompt, protected) = StructuredTranslation.prompt(sentences, config)
        val started = System.nanoTime(); var first = 0L; var chunks = 0; var complete: String? = null
        val streamed = StringBuilder()
        provider.generate(prompt).collect { event -> when (event) {
            is ProviderEvent.Delta -> { if (first == 0L) first = System.nanoTime(); chunks++; streamed.append(event.text) }
            is ProviderEvent.Complete -> { check(complete == null); complete = event.text }
        } }
        val raw = requireNotNull(complete); entry.put("raw", raw)
        check(streamed.toString() == raw) { "STREAM_CONTENT_MISMATCH" }
        val values = StructuredTranslation.validate(raw, sentences, protected)
        check(values.all { (_, text) -> text.any { it in '\u4e00'..'\u9fff' } }) { "NO_CHINESE_TRANSLATION" }
        entry.put("firstDeltaMs", (first - started) / 1e6).put("generationMs", (System.nanoTime() - started) / 1e6)
            .put("deltaEvents", chunks).put("deltaEventsAreTokenCounts", false)
            .put("translations", JSONArray(values.map { (source, text) -> JSONObject().put("id", source.id).put("source", source.text).put("translation", text) }))
    }
    val single = listOf(SourceSentence("p1-b1-s1", "We propose a memory-efficient method for translating scientific documents.", "p1-b1"))
    val batch = listOf(
        SourceSentence("p1-b2-s1", "The model processes 100 pages using 4 GB of memory [12].", "p1-b2"),
        SourceSentence("p1-b2-s2", "For ${'$'}E=mc^2${'$'}, retain the equation and see https://example.org/paper.", "p1-b2"),
        SourceSentence("p1-b2-s3", "The attention mechanism uses a key-value cache; the code is `cache.get(key)`.", "p1-b2"),
        SourceSentence("p1-b2-s4", "The proposed method reduces latency by 12.5% without changing the input order.", "p1-b2"),
    )
    try {
        runCase("cold-single") { infer(single, it) }
        runCase("warm-batch-four-with-protected-spans") { infer(batch, it) }
        runCase("real-queue-repair-and-cache-reopen") { entry ->
            val cache = ConcurrentHashMap<String, String>(); val calls = AtomicInteger()
            val attempts = JSONArray()
            val store = object : TranslationCache {
                override suspend fun get(key: String) = cache[key]
                override suspend fun put(key: String, text: String, translation: String, config: TranslationConfig) { cache[key] = translation }
            }
            val counted = object : TranslationProvider {
                override fun generate(prompt: ModelPrompt): Flow<ProviderEvent> = flow {
                    calls.incrementAndGet()
                    provider.generate(prompt).collect { event ->
                        if (event is ProviderEvent.Complete) attempts.put(JSONObject().put("input", prompt.user).put("raw", event.text))
                        emit(event)
                    }
                }
            }
            suspend fun open(id: String) {
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                try {
                    val done = CompletableDeferred<Unit>(); val values = ConcurrentHashMap<String, String>()
                    val queue = TranslationQueue(scope, store) { counted }
                    check(queue.submit(TranslationWork(id, 0, 0, batch, config,
                        committed = { s, value, _ -> values[s.id] = value },
                        finished = { failures, cancelled ->
                            if (failures.isEmpty() && !cancelled) done.complete(Unit)
                            else done.completeExceptionally(IllegalStateException("$failures cancelled=$cancelled"))
                        })))
                    done.await(); check(values.keys == batch.map { it.id }.toSet())
                    entry.put("translations", JSONArray(batch.map { s -> JSONObject().put("id", s.id).put("source", s.text).put("translation", values[s.id]) }))
                } finally { scope.cancel() }
            }
            open("first"); val before = calls.get(); check(before > 0)
            open("reopen"); check(calls.get() == before) { "CACHED_SENTENCE_RETRANSLATED" }
            entry.put("initialProviderCalls", before).put("reopenProviderCalls", calls.get() - before).put("cacheEntries", cache.size).put("attempts", attempts)
        }
        runCase("cancel-stream-and-infer-again") { entry ->
            val first = CompletableDeferred<Unit>(); var completed = false
            val prompt = StructuredTranslation.prompt(batch, config).first
            val generation = launch(Dispatchers.Default) {
                provider.generate(prompt).collect { event ->
                    if (event is ProviderEvent.Delta && event.text.isNotEmpty()) { first.complete(Unit); delay(5000) }
                    if (event is ProviderEvent.Complete) completed = true
                }
            }
            try {
                withTimeout(30000) { first.await() }
                val cancelStart = System.nanoTime(); generation.cancelAndJoin()
                entry.put("cancelAndJoinMs", (System.nanoTime() - cancelStart) / 1e6)
                check(!completed) { "CANCELLED_RESULT_WAS_COMMITTED" }
                infer(single, entry)
            } finally { generation.cancelAndJoin() }
        }
    } finally {
        provider.close()
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
        val passed = (0 until cases.length()).all { cases.getJSONObject(it).optString("status") == "passed" }
        report.put("status", if (passed) "passed" else "failed")
        outputFile.parentFile.mkdirs(); outputFile.writeText(report.toString(2) + "\n")
    }
    check(report.getString("status") == "passed") { "Real model check failed; see ${outputFile.path}" }
}
