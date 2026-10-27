package org.pararead.llama

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import org.junit.Assert.*
import org.junit.Test
import org.pararead.core.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class BackendFallbackTest {
    private val prompt = ModelPrompt("Translate.", "A model.", 1)
    private class FakeRuntime : LlamaRuntime {
        val loads = mutableListOf<Boolean>()
        val freed = mutableListOf<Long>()
        var failLoad = false
        var failGenerate = false
        var failCpu = false
        var failBenchmark = false
        var cancelled = false
        var blocking = false
        val entered = CountDownLatch(1)
        private val released = CountDownLatch(1)
        val output = "正确译文"
        override fun load(path: String, threads: Int, fullPrecisionKv: Boolean, rawCompletion: Boolean, vulkan: Boolean): Long {
            loads += vulkan
            if (vulkan && failLoad) error("VULKAN_DEVICE_UNAVAILABLE")
            if (!vulkan && failCpu) error("GGUF_LOAD_FAILED")
            return if (vulkan) 2L else 1L
        }
        override fun generate(handle: Long, prompt: String, callback: TokenCallback, expectedResults: Int, constrainJson: Boolean, greedy: Boolean): LongArray {
            entered.countDown()
            if (blocking) { check(released.await(5, TimeUnit.SECONDS)); error("INFERENCE_CANCELLED") }
            check(callback.onBytes((if (handle == 2L && failGenerate) "错误 GPU 草稿" else output).toByteArray()))
            if (handle == 2L && failGenerate) error("NONFINITE_LOGITS")
            return longArrayOf(4, 3, 10, 30, 2, 20, 1)
        }
        override fun architecture(handle: Long) = "qwen3"
        override fun device(handle: Long) = if (handle == 2L) "Test GPU" else "CPU"
        override fun benchmark(handle: Long, promptTokens: Int, generatedTokens: Int, callback: TokenCallback): DoubleArray {
            if (failBenchmark) error("VULKAN_DEVICE_LOST")
            return doubleArrayOf(10.0, 20.0, 100.0, 100.0)
        }
        override fun cancel(handle: Long) { cancelled = true; released.countDown() }
        override fun free(handle: Long) { freed += handle }
    }
    @Test fun cpuDefaultNeverLoadsVulkan() = runBlocking {
        val runtime = FakeRuntime()
        val provider = LlamaCppProvider("test", native = runtime)
        try {
            provider.generate(prompt).toList()
            assertEquals(listOf(false), runtime.loads)
            assertEquals(InferenceBackend.CPU, provider.backendStatus.actual)
        } finally { provider.closeAndJoin() }
        assertEquals(listOf(1L), runtime.freed)
    }
    @Test fun successfulGpuKeepsGpuAndClosesSession() = runBlocking {
        val runtime = FakeRuntime(); val session = mutableListOf<Boolean>()
        val provider = LlamaCppProvider("test", requestedBackend = InferenceBackend.VULKAN, native = runtime, onVulkanSession = { session += it })
        try {
            assertEquals(ProviderEvent.Complete(runtime.output), provider.generate(prompt).toList().last())
            assertEquals(InferenceBackend.VULKAN, provider.backendStatus.actual)
            assertEquals("Test GPU", provider.backendStatus.device)
            assertEquals(listOf(true), runtime.loads)
        } finally { provider.closeAndJoin() }
        assertEquals(listOf(true, false), session)
        assertEquals(listOf(2L), runtime.freed)
    }
    @Test fun loadFailureFallsBackOnceAndReusesCpu() = runBlocking {
        val runtime = FakeRuntime().apply { failLoad = true }
        val provider = LlamaCppProvider("test", requestedBackend = InferenceBackend.VULKAN, native = runtime)
        try {
            repeat(2) { provider.generate(prompt).toList() }
            assertEquals(listOf(true, false), runtime.loads)
            assertEquals(InferenceBackend.CPU, provider.backendStatus.actual)
            assertEquals("VULKAN_DEVICE_UNAVAILABLE", provider.backendStatus.fallbackReason)
        } finally { provider.closeAndJoin() }
    }
    @Test fun decodeFailureResetsPartialOutputAndFreesGpuBeforeCpuLoad() = runBlocking {
        val runtime = FakeRuntime().apply { failGenerate = true }
        val provider = LlamaCppProvider("test", requestedBackend = InferenceBackend.VULKAN, native = runtime)
        try {
            val events = provider.generate(prompt).toList()
            val restart = events.indexOf(ProviderEvent.Restart)
            assertTrue(restart > 0)
            assertEquals(listOf(ProviderEvent.Delta(runtime.output), ProviderEvent.Complete(runtime.output)), events.drop(restart + 1))
            assertEquals(listOf(true, false), runtime.loads)
            assertEquals(listOf(2L), runtime.freed)
            assertEquals(3L, provider.metrics!!.generatedTokens)
        } finally { provider.closeAndJoin() }
        assertEquals(listOf(2L, 1L), runtime.freed)
    }
    @Test fun userCancellationNeverTriggersCpuRetry() = runBlocking {
        val runtime = FakeRuntime().apply { blocking = true }
        val provider = LlamaCppProvider("test", requestedBackend = InferenceBackend.VULKAN, native = runtime)
        try {
            val task = launch(Dispatchers.Default) { provider.generate(prompt).collect() }
            assertTrue(withContext(Dispatchers.IO) { runtime.entered.await(5, TimeUnit.SECONDS) })
            withTimeout(5_000) { task.cancelAndJoin() }
            assertTrue(runtime.cancelled)
            assertEquals(listOf(true), runtime.loads)
            assertEquals("", provider.backendStatus.fallbackReason)
        } finally { provider.closeAndJoin() }
    }
    @Test fun cpuFailureIsSurfacedWithoutRetryLoop() = runBlocking {
        val runtime = FakeRuntime().apply { failLoad = true; failCpu = true }
        val provider = LlamaCppProvider("test", requestedBackend = InferenceBackend.VULKAN, native = runtime)
        try {
            try { provider.generate(prompt).toList(); fail("Expected CPU load failure") }
            catch (e: IllegalStateException) { assertEquals("GGUF_LOAD_FAILED", e.message) }
            assertEquals(listOf(true, false), runtime.loads)
        } finally { provider.closeAndJoin() }
    }
    @Test fun failedGpuBenchmarkDiscardsRunRatherThanReturningCpuNumbers() = runBlocking {
        val runtime = FakeRuntime().apply { failBenchmark = true }
        val provider = LlamaCppProvider("test", requestedBackend = InferenceBackend.VULKAN, native = runtime)
        try {
            try { provider.benchmark(); fail("Expected failed benchmark") }
            catch (e: IllegalStateException) { assertTrue(e.message!!.contains("本轮结果已丢弃")) }
            assertEquals(listOf(2L), runtime.freed)
            assertTrue(provider.backendStatus.fallbackReason.isNotEmpty())
            assertNull(provider.backendStatus.actual)
        } finally { provider.closeAndJoin() }
    }
    @Test fun cancelledWaitingProviderDoesNotLoadOrClearAnotherGpuSession() = runBlocking {
        val firstRuntime = FakeRuntime(); val secondRuntime = FakeRuntime()
        val firstSession = mutableListOf<Boolean>(); val secondSession = mutableListOf<Boolean>()
        val first = LlamaCppProvider("first", requestedBackend = InferenceBackend.VULKAN, native = firstRuntime, onVulkanSession = { firstSession += it })
        val second = LlamaCppProvider("second", requestedBackend = InferenceBackend.VULKAN, native = secondRuntime, onVulkanSession = { secondSession += it })
        try {
            first.generate(prompt).toList()
            val waiting = launch(Dispatchers.Default) { second.generate(prompt).collect() }
            delay(150)
            withTimeout(3_000) { waiting.cancelAndJoin(); second.closeAndJoin() }
            assertTrue(secondRuntime.loads.isEmpty())
            assertTrue(secondSession.isEmpty())
            assertEquals(listOf(true), firstSession)
        } finally { first.closeAndJoin(); second.closeAndJoin() }
        assertEquals(listOf(true, false), firstSession)
    }
}
