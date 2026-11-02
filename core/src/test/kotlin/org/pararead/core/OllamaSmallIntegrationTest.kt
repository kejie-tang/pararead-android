package org.pararead.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

/** Opt-in only: two short original sentences, never a downloaded paper or a stress test.
 * Caller controls the local server's resource limits; CPU-only testing requires explicit permission.
 */
class OllamaSmallIntegrationTest {
    @Test fun realSmallModelRepairsCopiedCacheAndPreservesGoodCache() = runBlocking {
        val endpoint = System.getenv("PARAREAD_OLLAMA_BASE_URL")
        assumeTrue("Local model test is opt-in", !endpoint.isNullOrBlank())
        val model = System.getenv("PARAREAD_OLLAMA_MODEL") ?: "qwen3:0.6b"
        val config = TranslationConfig(provider = "api", model = model, modelHash = "local-small-smoke", batchSize = 2)
        val bad = SourceSentence("p1-b0-s0", "The reader remembers the last page.", "p1-b0")
        val good = SourceSentence("p2-b0-s0", "The library stores translations safely.", "p2-b0")
        val originalGood = "书库会安全保存译文。"
        val values = ConcurrentHashMap<String, String>().apply {
            put(contentKey(bad.text, config), bad.text)
            put(contentKey(good.text, config), originalGood)
        }
        val cache = object : TranslationCache {
            override suspend fun get(key: String) = values[key]
            override suspend fun put(key: String, text: String, translation: String, config: TranslationConfig) { values[key] = translation }
        }
        val delegate = OpenAICompatibleProvider(requireNotNull(endpoint), config.model, ollamaThinkingOff = true)
        val calls = AtomicInteger()
        val provider = object : TranslationProvider {
            override fun generate(prompt: ModelPrompt) = flow {
                calls.incrementAndGet()
                assertTrue(prompt.user.endsWith("/no_think"))
                delegate.generate(prompt).collect { emit(it) }
            }
        }
        val workerScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val queue = TranslationQueue(workerScope, cache) { provider }
            suspend fun translate(id: String): Map<String, String> = withTimeout(90_000) {
                val committed = ConcurrentHashMap<String, String>()
                val done = CompletableDeferred<Pair<List<Pair<String, String>>, Boolean>>()
                assertTrue(queue.submit(TranslationWork(id, 0, 0, listOf(bad, good), config,
                    committed = { sentence, translated, _ -> committed[sentence.id] = translated },
                    finished = { failures, cancelled -> done.complete(failures to cancelled) })))
                val (failures, cancelled) = done.await()
                assertFalse(cancelled); assertTrue(failures.isEmpty(), failures.toString())
                committed.toMap()
            }
            val first = translate("repair-small-ollama")
            val repaired = assertNotNull(first[bad.id])
            assertNotEquals(bad.text, repaired)
            assertTrue(repaired.any { it in '\u4e00'..'\u9fff' })
            assertTrue(repaired.contains("页"), "The short page sentence must not be replaced by generic instructions")
            assertTrue(TranslationQuality.accepts(bad.text, repaired, config))
            assertEquals(repaired, values[contentKey(bad.text, config)])
            assertEquals(originalGood, first[good.id])
            val afterRepair = calls.get()
            assertTrue(afterRepair in 1..3, "Recovery must be bounded")
            assertEquals(first, translate("reopen-small-ollama"))
            assertEquals(afterRepair, calls.get(), "Good translations must reopen without inference")
            assertEquals(originalGood, values[contentKey(good.text, config)])
            println("OLLAMA_SMALL_TEST model=$model requests=$afterRepair copiedCacheRepaired=true goodCachePreserved=true reopenInference=0 repaired=$repaired")
        } finally { workerScope.cancel(); delegate.close() }
    }
}
