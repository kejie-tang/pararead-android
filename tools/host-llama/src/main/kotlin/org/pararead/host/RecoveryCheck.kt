package org.pararead.host

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import org.json.JSONArray
import org.json.JSONObject
import org.pararead.core.*
import org.pararead.llama.LlamaCppProvider
import org.pararead.llama.NativeBridge
import org.pararead.probes.ResearchSentenceProvider
import org.pararead.probes.queueProbeFixtures
import org.pararead.probes.heldOutProbeFixtures
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** Real production queue and real model retries; replayed first answers are explicitly labelled. */
suspend fun runRecoveryCheck(args: List<String>) {
    require(args.size == 2 || args.size == 3 && args[2] in setOf("text-only", "user-only", "user-only-heldout")) { "recovery report.json manifest.json [text-only|user-only|user-only-heldout]" }
    val textOnly = args.getOrNull(2) == "text-only"
    val userOnly = args.getOrNull(2)?.startsWith("user-only") == true
    val output = File(args[0]); val manifest = JSONArray(File(args[1]).readText())
    val models = JSONArray()
    val report = JSONObject().put("platform", "${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
        .put("physicalAndroidDevice", false).put("hardware", System.getenv("PARAREAD_HARDWARE") ?: "not recorded").put("threads", 6)
        .put("engine", NativeBridge.version()).put("policy", "partial-row-commit; targeted JSON retry; strict single-text final retry")
        .put("diagnosticUserOnlyAdapter", userOnly).put("adapterInAndroidApp", false)
        .put("formatPassCertifiesSemanticAccuracy", false).put("models", models)
    fun save() { output.parentFile.mkdirs(); output.writeText(report.toString(2) + "\n") }
    val fixtures = if (args.getOrNull(2) == "user-only-heldout") heldOutProbeFixtures() else queueProbeFixtures()
    val config = TranslationConfig(sourceLanguage = "en", targetLanguage = "zh-CN")
    for (i in 0 until manifest.length()) {
        val source = manifest.getJSONObject(i); val file = File("artifacts/models", source.getString("file"))
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input -> val bytes = ByteArray(262144); while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) } }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        require(hash == source.getString("sha256") && file.length() == source.getLong("bytes")) { "MODEL_MANIFEST_MISMATCH" }
        val model = JSONObject().put("source", source).put("sha256", hash).put("cases", JSONArray()); models.put(model); save()
        val provider = LlamaCppProvider(file.path, 6, fullPrecisionKv = source.optBoolean("fullPrecisionKv"), greedy = source.optBoolean("greedy"), rawCompletion = source.optBoolean("rawCompletion"))
        try {
            suspend fun queueCase(name: String, sentences: List<SourceSentence>, replay: String? = null) {
                val entry = JSONObject().put("fixture", name).put("replayedFirstAnswer", replay != null).put("attempts", JSONArray())
                model.getJSONArray("cases").put(entry)
                val values = ConcurrentHashMap<String, String>(); val committed = JSONArray(); entry.put("commits", committed)
                val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
                var calls = 0
                var nativeCalls = 0
                val wrapped = object : TranslationProvider {
                    override fun generate(prompt: ModelPrompt) = flow {
                        val attempt = JSONObject().put("format", prompt.outputFormat.name).put("system", prompt.system).put("input", prompt.user)
                        entry.getJSONArray("attempts").put(attempt); calls++
                        if (calls == 1 && replay != null) {
                            attempt.put("replayed", true).put("raw", replay); emit(ProviderEvent.Complete(replay))
                        } else if (userOnly) {
                            val real = JSONArray(); attempt.put("nativeCalls", real)
                            ResearchSentenceProvider(provider, source) { record -> real.put(record); nativeCalls++ }.generate(prompt).collect { event ->
                                if (event is ProviderEvent.Complete) attempt.put("raw", event.text)
                                emit(event)
                            }
                        } else provider.generate(prompt).collect { event ->
                            if (event is ProviderEvent.Complete) {
                                attempt.put("raw", event.text)
                                provider.metrics?.let { attempt.put("tokensPerSecond", it.tokensPerSecond).put("generatedTokens", it.generatedTokens) }
                            }
                            emit(event)
                        }
                    }
                }
                val store = object : TranslationCache {
                    override suspend fun get(key: String) = values[key]
                    override suspend fun put(key: String, text: String, translation: String, config: TranslationConfig) { values[key] = translation }
                }
                val started = System.nanoTime()
                try { withTimeout(120000) {
                    val done = CompletableDeferred<Unit>()
                    val queue = TranslationQueue(scope, store) { wrapped }
                    queue.submit(TranslationWork(name, 0, 0, sentences, config,
                        committed = { s, text, _ -> committed.put(JSONObject().put("id", s.id).put("translation", text)) },
                        finished = { failures, cancelled ->
                            entry.put("failures", JSONArray(failures.map { JSONObject().put("id", it.first).put("reason", it.second) }))
                                .put("cancelled", cancelled).put("status", if (!cancelled && failures.isEmpty() && committed.length() == sentences.size &&
                                    (0 until committed.length()).all { committed.getJSONObject(it).getString("translation").any { ch -> ch in '\u4e00'..'\u9fff' } }) "passed" else "failed")
                            done.complete(Unit)
                        }))
                    done.await()
                    val before = calls; val reopened = CompletableDeferred<Unit>()
                    queue.submit(TranslationWork("$name-reopen", 0, 0, sentences.filter { s -> (0 until committed.length()).any { committed.getJSONObject(it).getString("id") == s.id } }, config,
                        committed = { _, _, _ -> }, finished = { failures, _ -> check(failures.isEmpty()); reopened.complete(Unit) }))
                    reopened.await(); check(calls == before) { "CACHED_SENTENCE_RETRANSLATED" }; entry.put("cacheReopenCalls", calls - before)
                } } catch (e: Exception) { entry.put("status", "failed").put("error", e.toString()) }
                finally { scope.cancel() }
                entry.put("totalMs", (System.nanoTime() - started) / 1e6).put("providerCalls", calls)
                if (userOnly) entry.put("nativeCalls", nativeCalls)
                save()
                println("${file.name} $name: ${entry.optString("status")} calls=$calls")
            }
            if (!textOnly) for ((name, sentences) in fixtures) queueCase(name, sentences)
            if (!textOnly && file.name in setOf("Qwen3-0.6B-Q4_0.gguf", "Qwen3.5-0.8B-Q4_0.gguf")) {
                val previous = File("docs/reports/android-small-initial-${file.name.removeSuffix(".gguf")}.json")
                val cases = JSONObject(previous.readText()).getJSONArray("cases")
                val raw = (0 until cases.length()).map { cases.getJSONObject(it) }.first { it.getString("fixture") == "protected-batch" }.getString("raw")
                queueCase("recorded-android-first-answer-real-recovery", fixtures.getValue("protected-batch"), raw)
            }
            val textCases = JSONArray(); model.put("textOnlyDiagnostics", textCases)
            for (sentence in if (userOnly) emptyList() else fixtures.getValue("protected-batch")) {
                val (prompt, protected) = StructuredTranslation.textPrompt(sentence, config)
                val entry = JSONObject().put("source", sentence.text).put("system", prompt.system).put("input", prompt.user); textCases.put(entry)
                var raw = ""
                try { withTimeout(120000) {
                    provider.generate(prompt).collect { if (it is ProviderEvent.Complete) raw = it.text }
                    entry.put("translation", StructuredTranslation.validateText(raw, protected, config)).put("status", "passed")
                } } catch (e: Exception) { entry.put("status", "failed").put("error", e.toString()) }
                entry.put("raw", raw); save()
            }
        } finally { provider.closeAndJoin() }
    }
    save()
}
