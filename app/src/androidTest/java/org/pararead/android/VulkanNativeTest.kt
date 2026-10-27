package org.pararead.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.pararead.core.*
import org.pararead.llama.*
import java.io.File

@RunWith(AndroidJUnit4::class)
class VulkanNativeTest {
    /** Opt-in: native CPU baseline followed by Vulkan or an explicitly recorded CPU fallback. */
    @Test fun actualBackendAndOutputAreRecorded(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val path = args.getString("vulkanGgufPath")
        assumeTrue("Supply vulkanGgufPath for GPU test", path != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = Settings(context)
        val report = JSONObject().put("device", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
        for (backend in InferenceBackend.entries) {
            val provider = LlamaCppProvider(path!!, 6, singleResult = true, requestedBackend = backend, onVulkanSession = settings::markVulkanSession)
            try {
                val source = listOf(SourceSentence("one", "Local models translate documents offline.", "g"))
                val (prompt, protected) = StructuredTranslation.prompt(source, TranslationConfig())
                var complete: String? = null
                withTimeout(180_000) { provider.generate(prompt).collect { if (it is ProviderEvent.Complete) complete = it.text } }
                val translation = StructuredTranslation.validate(checkNotNull(complete), source, protected).single().second
                assertTrue(translation.any { it in '\u4e00'..'\u9fff' })
                assertNotNull(provider.backendStatus.actual)
                if (backend == InferenceBackend.CPU) assertEquals(InferenceBackend.CPU, provider.backendStatus.actual)
                else if (provider.backendStatus.actual == InferenceBackend.CPU) assertTrue(provider.backendStatus.fallbackReason.isNotEmpty())
                val benchmark = withTimeout(60_000) { provider.benchmark(32, 8) }
                assertTrue(benchmark.all { it.isFinite() && it > 0 })
                report.put(backend.name, JSONObject().put("actualBackend", provider.backendStatus.actual!!.name)
                    .put("device", provider.backendStatus.device).put("fallbackReason", provider.backendStatus.fallbackReason)
                    .put("translation", translation).put("pp32", benchmark[0]).put("tg8", benchmark[1]))
            } finally { provider.closeAndJoin() }
        }
        // A CPU session after loading Vulkan must remain CPU, even with the GPU plugin registered.
        val finalCpu = LlamaCppProvider(path!!, 6)
        try {
            assertTrue(finalCpu.benchmark(16, 2).all { it.isFinite() && it > 0 })
            assertEquals(InferenceBackend.CPU, finalCpu.backendStatus.actual)
            report.put("cpuAfterVulkan", finalCpu.backendStatus.actual!!.name)
        } finally { finalCpu.closeAndJoin() }
        File(context.filesDir, "vulkan-native-test.json").writeText(report.toString(2))
    }
}
