package org.pararead.host

import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import org.pararead.core.*
import org.pararead.llama.LlamaCppProvider
import org.pararead.llama.NativeBridge
import org.pararead.probes.translationProbeRequest
import java.io.File
import java.security.MessageDigest

/** Diagnostic only: a plaintext pass does not establish compatibility with the app's JSON protocol. */
suspend fun runPlainModelProbe(args: List<String>) {
    require(args.size == 2 || args.size == 3 && args[2] == "literal") { "plain report.json manifest.json [literal]" }
    val literal = args.getOrNull(2) == "literal"
    val output = File(args[0]); val manifest = JSONArray(File(args[1]).readText())
    val models = JSONArray()
    val report = JSONObject().put("diagnosticOnly", true).put("productionJsonProtocol", false)
        .put("physicalAndroidDevice", false).put("platform", System.getProperty("os.name") + " " + System.getProperty("os.arch"))
        .put("hardware", System.getenv("PARAREAD_HARDWARE") ?: "not recorded").put("engine", NativeBridge.version())
        .put("formatPassCertifiesSemanticAccuracy", false).put("promptTemplate", "manifest promptProfile; default user-only")
        .put("threads", 6).put("literalProtectedContent", literal).put("models", models)
    fun save() { output.parentFile.mkdirs(); output.writeText(report.toString(2) + "\n") }
    val fixtures = linkedMapOf("short" to SpeedTest.SOURCE, "long" to SpeedTest.LONG_SOURCE,
        "protected" to "The model processes 100 pages using 4 GB of memory [12]. For ${'$'}E=mc^2${'$'}, see https://example.org/paper.",
        "negation" to "The increase was not statistically significant. The result may indicate an association, but it does not establish causation.",
        "protected-numeric-single" to "The model processes 100 pages using 4 GB of memory [12].",
        "protected-equation-single" to "For ${'$'}E=mc^2${'$'}, retain the equation and see https://example.org/paper.")
    for (i in 0 until manifest.length()) {
        val source = manifest.getJSONObject(i)
        val file = File("artifacts/models", source.getString("file"))
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input -> val bytes = ByteArray(262144); while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) } }
        require(file.length() == source.getLong("bytes") && digest.digest().joinToString("") { "%02x".format(it) } == source.getString("sha256")) { "MODEL_MANIFEST_MISMATCH" }
        val model = JSONObject().put("source", source).put("cases", JSONArray()); models.put(model)
        val provider = LlamaCppProvider(file.path, 6, constrainJson = false,
            fullPrecisionKv = source.optBoolean("fullPrecisionKv"), greedy = source.optBoolean("greedy"), rawCompletion = source.optBoolean("rawCompletion"))
        try {
            for ((name, text) in fixtures) {
                val protected = StructuredTranslation.protect(text)
                val input = if (literal) text else protected.text
                val request = translationProbeRequest(source, input)
                val prompt = request.prompt
                val entry = JSONObject().put("fixture", name).put("sourceText", text).put("system", prompt.system).put("user", prompt.user).put("markerStyle", request.markerStyle)
                model.getJSONArray("cases").put(entry)
                val raw = StringBuilder()
                try { withTimeout(120000) {
                    provider.generate(prompt).collect { if (it is ProviderEvent.Delta) raw.append(it.text) }
                    val restored = if (literal) raw.toString().trim().also { value ->
                        require(StructuredTranslation.protect(value).tokens.sorted() == protected.tokens.sorted()) { "LITERAL_PROTECTED_CONTENT_MISMATCH" }
                    } else protected.restore(request.decode(raw.toString().trim()))
                    check(restored.any { it in '\u4e00'..'\u9fff' }) { "NO_CHINESE_TRANSLATION" }
                    entry.put("status", "passed").put("restored", restored)
                } } catch (e: Exception) { entry.put("status", "failed").put("error", e.toString()) }
                entry.put("raw", raw.toString())
                provider.metrics?.let { entry.put("generatedTokens", it.generatedTokens).put("tokensPerSecond", it.tokensPerSecond) }
                save()
                println("${source.getString("file")} plain $name: ${entry.optString("status")}")
            }
        } finally { provider.closeAndJoin() }
    }
    save()
}
