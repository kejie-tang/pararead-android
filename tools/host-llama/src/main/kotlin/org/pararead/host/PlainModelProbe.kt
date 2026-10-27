package org.pararead.host

import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.collect
import org.json.JSONArray
import org.json.JSONObject
import org.pararead.core.*
import org.pararead.llama.LlamaCppProvider
import java.io.File

/** Diagnostic only: a plaintext pass does not establish compatibility with the app's JSON protocol. */
suspend fun runPlainModelProbe(args: List<String>) {
    require(args.size == 2) { "plain report.json manifest.json" }
    val output = File(args[0]); val manifest = JSONArray(File(args[1]).readText())
    val models = JSONArray()
    val report = JSONObject().put("diagnosticOnly", true).put("productionJsonProtocol", false)
        .put("physicalAndroidDevice", false).put("platform", System.getProperty("os.name") + " " + System.getProperty("os.arch"))
        .put("threads", 6).put("models", models)
    fun save() { output.parentFile.mkdirs(); output.writeText(report.toString(2) + "\n") }
    val fixtures = linkedMapOf("short" to SpeedTest.SOURCE, "long" to SpeedTest.LONG_SOURCE,
        "protected" to "The model processes 100 pages using 4 GB of memory [12]. For ${'$'}E=mc^2${'$'}, see https://example.org/paper.",
        "negation" to "The increase was not statistically significant. The result may indicate an association, but it does not establish causation.")
    for (i in 0 until manifest.length()) {
        val source = manifest.getJSONObject(i)
        val model = JSONObject().put("source", source).put("cases", JSONArray()); models.put(model)
        val provider = LlamaCppProvider(File("artifacts/models", source.getString("file")).path, 6, constrainJson = false)
        try {
            for ((name, text) in fixtures) {
                val protected = StructuredTranslation.protect(text)
                val user = if (source.getString("file").startsWith("LMT"))
                    "Translate the following text from English into Chinese:\nEnglish: ${protected.text}\nChinese:"
                else "Translate the following text from English to Simplified Chinese.\n${protected.text}"
                val entry = JSONObject().put("fixture", name).put("sourceText", text).put("user", user)
                model.getJSONArray("cases").put(entry)
                val raw = StringBuilder()
                try { withTimeout(120000) {
                    provider.generate(ModelPrompt("", user)).collect { if (it is ProviderEvent.Delta) raw.append(it.text) }
                    val restored = protected.restore(raw.toString().trim())
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
