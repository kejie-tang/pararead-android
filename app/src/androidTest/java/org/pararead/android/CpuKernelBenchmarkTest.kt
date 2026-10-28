package org.pararead.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.pararead.core.*
import org.pararead.llama.*
import java.io.File
import java.security.MessageDigest

/** Opt-in real GGUF check. The same test APK can compare alpha.8 and the new CPU build. */
@RunWith(AndroidJUnit4::class)
class CpuKernelBenchmarkTest {
    @Test fun realTranslationAndThreeEngineRuns(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val path = args.getString("cpuGgufPath")
        assumeTrue("Supply cpuGgufPath for real CPU kernel validation", path != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val model = File(path!!)
        val digest = MessageDigest.getInstance("SHA-256")
        model.inputStream().buffered().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        val threads = args.getString("cpuThreads")?.toInt() ?: 6
        val report = JSONObject().put("modelSha256", digest.digest().joinToString("") { "%02x".format(it) })
            .put("modelBytes", model.length()).put("threads", threads)
            .put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            .put("protocol", "16-token warmup; pp512 then tg128; pl1; 3 runs; no sampler/grammar/token callback; ctx4096; batch512; ubatch128")
        val provider = LlamaCppProvider(path, threads, singleResult = true)
        try {
            val source = listOf(SourceSentence("one", "Local models translate documents offline.", "g"))
            val (prompt, protected) = StructuredTranslation.prompt(source, TranslationConfig())
            var complete: String? = null
            withTimeout(120_000) { provider.generate(prompt).collect { if (it is ProviderEvent.Complete) complete = it.text } }
            val translation = StructuredTranslation.validate(checkNotNull(complete), source, protected).single().second
            assertTrue(translation.any { it in '\u4e00'..'\u9fff' })
            assertEquals(InferenceBackend.CPU, provider.backendStatus.actual)
            val info = NativeBridge.systemInfo()
            args.getString("expectedCpuPlugin")?.let { assertTrue(info, info.contains("CPU_PLUGIN=$it")) }
            report.put("systemInfo", info).put("translation", translation)
                .put("actualBackend", provider.backendStatus.actual!!.name)
            val samples = JSONArray()
            repeat(3) {
                val measured = withTimeout(120_000) { provider.benchmark() }
                assertEquals(4, measured.size)
                assertTrue(measured.all { it.isFinite() && it > 0 })
                samples.put(JSONObject().put("pp512", measured[0]).put("tg128", measured[1])
                    .put("prefillMs", measured[2]).put("decodeMs", measured[3]))
            }
            report.put("samples", samples)
        } finally { provider.closeAndJoin() }
        File(context.filesDir, "cpu-kernel-benchmark.json").writeText(report.toString(2))
    }
}
