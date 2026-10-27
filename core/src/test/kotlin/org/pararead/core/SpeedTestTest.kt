package org.pararead.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import org.json.JSONObject
import org.junit.Test
import kotlin.test.*

class SpeedTestTest {
    private class Provider(val output: String = "{\"translations\":[{\"id\":\"s1\",\"translation\":\"我们提出一种翻译科学文档的内存高效方法。\"}]}", val measured: Boolean = true) : MeasuredTranslationProvider {
        var calls = 0
        override var metrics: GenerationMetrics? = null
        override fun generate(prompt: ModelPrompt) = flow {
            calls++
            metrics = null
            delay(1)
            emit(ProviderEvent.Delta(output)) // one event is deliberately not one token
            delay(1)
            if (measured) metrics = GenerationMetrics(100, 40, 100_000_000, 2_000_000_000)
            emit(ProviderEvent.Complete(output))
        }
    }
    @Test fun fourRealCallsAndNativeCountsSurviveExportRoundTrip() = runBlocking {
        val provider = Provider(); val progress = mutableListOf<Int>()
        val result = SpeedTest.run(provider) { progress += it }
        assertEquals(listOf(1, 2, 3, 4), progress)
        assertEquals(4, provider.calls)
        assertEquals(20.0, result.warmMedian { it.metrics.tokensPerSecond })
        assertEquals(40L, result.first.metrics.generatedTokens)
        assertEquals(result, SpeedTestResult.fromJson(JSONObject(result.toJson().toString())))
        val varied = result.copy(samples = result.samples.mapIndexed { i, s -> s.copy(firstResponseMs = 1.0, totalMs = 100.0 + listOf(999, 30, 10, 20)[i]) })
        assertEquals(120.0, varied.warmMedian { it.totalMs })
    }
    @Test fun invalidTranslationsAndMissingNativeMetricsNeverBecomeResults() = runBlocking {
        for (provider in listOf(Provider("{\"translations\":[]}"), Provider(measured = false), Provider("{\"translations\":[{\"id\":\"s1\",\"translation\":\"English only\"}]}"))) {
            val error = runCatching { SpeedTest.run(provider) }.exceptionOrNull()
            assertNotNull(error)
            assertTrue(error is IllegalStateException || error is IllegalArgumentException)
            assertEquals(1, provider.calls)
        }
    }
    @Test fun cancellationWaitsForProducerAndDoesNotStartAnotherSample() = runBlocking {
        val entered = CompletableDeferred<Unit>(); val released = CompletableDeferred<Unit>()
        var calls = 0
        val provider = object : MeasuredTranslationProvider {
            override val metrics: GenerationMetrics? = null
            override fun generate(prompt: ModelPrompt) = flow<ProviderEvent> {
                calls++; entered.complete(Unit)
                try { awaitCancellation() } finally { withContext(NonCancellable) { delay(30); released.complete(Unit) } }
            }
        }
        val task = launch { SpeedTest.run(provider) }
        entered.await(); task.cancelAndJoin()
        assertTrue(released.isCompleted); assertEquals(1, calls)
    }
    @Test fun incompleteOrInvalidReportsAreRejected() {
        assertFailsWith<IllegalArgumentException> { SpeedTestResult(emptyList()) }
        assertFailsWith<IllegalArgumentException> { GenerationMetrics(10, 0, 10, 10) }
        assertFailsWith<IllegalArgumentException> { SpeedSample(20.0, 10.0, GenerationMetrics(1, 1, 1, 1), "译文") }
    }
    @Test fun previousShortReportsStillLoadWithoutNewTimingFields() {
        val sample = SpeedSample(1.0, 3.0, GenerationMetrics(10, 4, 100, 200), "译文")
        val old = SpeedTestResult(List(4) { sample }).toJson().put("methodVersion", 1)
        repeat(4) { index -> old.getJSONArray("samples").getJSONObject(index).apply {
            remove("samplingNanos"); remove("decodeNanos"); remove("callbackNanos")
        } }
        val restored = SpeedTestResult.fromJson(old)
        assertEquals(SpeedTest.SOURCE, restored.source)
        assertEquals(0L, restored.first.metrics.samplingNanos)
    }
}
