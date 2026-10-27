package org.pararead.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
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
        val fixtures = listOf(
            listOf(SourceSentence("a", SpeedTest.SOURCE, "p1")),
            listOf(SourceSentence("a", "The model processes 100 pages using 4 GB of memory [12].", "p1"),
                SourceSentence("b", "For ${'$'}E=mc^2${'$'}, retain the equation and see https://example.org/paper.", "p1")))
        try {
            for ((index, sentences) in fixtures.withIndex()) {
                val entry = JSONObject().put("fixture", if (index == 0) "short" else "protected-batch"); cases.put(entry)
                val raw = StringBuilder()
                try { withTimeout(120000) {
                    val (prompt, protected) = StructuredTranslation.prompt(sentences, TranslationConfig(sourceLanguage = "en", targetLanguage = "zh-CN"))
                    provider.generate(prompt).collect { if (it is ProviderEvent.Delta) raw.append(it.text) }
                    val translations = StructuredTranslation.validate(raw.toString(), sentences, protected)
                    check(translations.all { (_, value) -> value.any { it in '\u4e00'..'\u9fff' } })
                    entry.put("status", "passed")
                } } catch (e: Exception) { entry.put("status", "failed").put("error", e.toString()) }
                entry.put("raw", raw.toString())
                provider.metrics?.let { entry.put("generatedTokens", it.generatedTokens).put("tokensPerSecond", it.tokensPerSecond) }
            }
            report.put("architecture", provider.architecture)
        } finally {
            provider.closeAndJoin()
            File(context.filesDir, "model-compatibility.json").writeText(report.toString(2))
        }
        assertEquals(report.toString(), "passed", cases.getJSONObject(0).getString("status"))
    }
}
