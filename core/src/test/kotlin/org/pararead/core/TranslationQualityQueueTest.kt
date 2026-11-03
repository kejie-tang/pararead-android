package org.pararead.core

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import kotlin.test.*

class TranslationQualityQueueTest {
    private val english = "The proposed attention mechanism preserves contextual information across distant observations while reducing the computational cost of inference."
    private val chinese = "所提出的注意力机制保留了相距较远的观测中的上下文信息，同时降低了推理的计算成本。"
    private val warning = " *****（translation_language_or_source_copy）"
    private fun json(value: String) = JSONObject().put("translations", JSONArray().put(JSONObject().put("id", "s1").put("translation", value))).toString()

    private class Cache(val values: MutableMap<String, String> = mutableMapOf()) : TranslationCache {
        val writes = mutableListOf<String>()
        override suspend fun get(key: String) = values[key]
        override suspend fun put(key: String, text: String, translation: String, config: TranslationConfig) {
            values[key] = translation; writes += key
        }
    }

    @Test fun copiedEnglishIsKeptWithWarningAfterOneGenerationAndReopensWithoutInference() = runTest {
        for (target in listOf("zh-CN", "zh-TW")) {
            val config = TranslationConfig(targetLanguage = target)
            val key = contentKey(english, config)
            val cache = Cache()
            var calls = 0
            val provider = object : TranslationProvider { override fun generate(prompt: ModelPrompt) = flow {
                calls++; assertEquals(ModelOutputFormat.TRANSLATION_JSON, prompt.outputFormat)
                emit(ProviderEvent.Complete(json(english)))
            } }
            val queue = TranslationQueue(backgroundScope, cache) { provider }
            repeat(2) { n ->
                val done = CompletableDeferred<Unit>()
                queue.submit(TranslationWork("copy-$target-$n", 0, 0, listOf(SourceSentence("bad", english, "g")), config,
                    committed = { _, value, _ -> assertEquals(english + warning, value); assertEquals(value, cache.values[key]) },
                    finished = { failures, cancelled -> assertFalse(cancelled); assertTrue(failures.isEmpty()); done.complete(Unit) }))
                done.await()
            }
            assertEquals(1, calls); assertEquals(listOf(key), cache.writes)
        }
    }

    @Test fun unmarkedBadCacheGetsOneAttemptButGoodCacheAndFingerprintAreRetained() = runTest {
        val config = TranslationConfig()
        val bad = SourceSentence("bad", english, "g")
        val good = SourceSentence("good", "The model retains the previous observations.", "g")
        val badKey = contentKey(bad.text, config); val goodKey = contentKey(good.text, config)
        val fingerprint = config.fingerprint()
        val cache = Cache(mutableMapOf(badKey to "中译 $english", goodKey to "该模型保留先前的观测。"))
        val prompts = mutableListOf<ModelPrompt>(); val commits = mutableListOf<String>()
        val provider = object : TranslationProvider { override fun generate(prompt: ModelPrompt) = flow {
            prompts += prompt; assertFalse(prompt.user.contains(good.text))
            emit(ProviderEvent.Complete(json(english)))
        } }
        val queue = TranslationQueue(backgroundScope, cache) { provider }
        suspend fun open(id: String) {
            val done = CompletableDeferred<Unit>()
            assertTrue(queue.submit(TranslationWork(id, 0, 0, listOf(bad, good), config,
                committed = { source, value, key -> assertEquals(value, cache.values[key]); commits += source.id },
                finished = { failures, cancelled -> assertFalse(cancelled); assertTrue(failures.isEmpty()); done.complete(Unit) })))
            done.await()
        }
        open("first")
        assertEquals(listOf("good", "bad"), commits)
        assertEquals(english + warning, cache.values[badKey])
        assertEquals("该模型保留先前的观测。", cache.values[goodKey])
        open("reopen")
        assertEquals(1, prompts.size); assertEquals(listOf(badKey), cache.writes)
        assertEquals(2, cache.values.size); assertEquals(fingerprint, config.fingerprint())
        assertTrue(config.fingerprint().contains("validator=6"))
    }

    @Test fun longSentenceUsesEachMissingFragmentOnceAndConsolidatesWarningsAtTheEnd() = runTest {
        val config = TranslationConfig()
        val source = List(25) { english }.joinToString(" ")
        val sentence = SourceSentence("long", source, "g")
        val parts = StructuredTranslation.fragments(source)
        assertTrue(parts.size >= 3)
        val originalKey = contentKey(source, config)
        val firstKey = contentKey(parts.first(), config)
        val cache = Cache(mutableMapOf(originalKey to source, firstKey to parts.first()))
        // One already marked fragment, one known-good fragment, and an unmarked bad first fragment.
        cache.values[contentKey(parts[1], config)] = parts[1] + warning
        parts.drop(2).forEach { cache.values[contentKey(it, config)] = chinese }
        var calls = 0
        val provider = object : TranslationProvider { override fun generate(prompt: ModelPrompt) = flow {
            calls++; emit(ProviderEvent.Complete(json(parts.first())))
        } }
        val queue = TranslationQueue(backgroundScope, cache) { provider }
        repeat(2) { n ->
            val done = CompletableDeferred<Unit>()
            queue.submit(TranslationWork("fragments-$n", 0, 0, listOf(sentence), config,
                committed = { _, value, key ->
                    assertEquals(parts[0] + parts[1] + chinese.repeat(parts.size - 2) + warning, value)
                    assertEquals(originalKey, key)
                    assertEquals(1, Regex("translation_language_or_source_copy").findAll(value).count())
                }, finished = { failures, cancelled -> assertFalse(cancelled); assertTrue(failures.isEmpty()); done.complete(Unit) }))
            done.await()
        }
        assertEquals(1, calls); assertEquals(listOf(firstKey, originalKey), cache.writes)
        parts.drop(2).forEach { assertEquals(chinese, cache.values[contentKey(it, config)]) }
    }

    @Test fun failedFragmentKeepsCompletedTextAndDoesNotRegenerateTheWholeSentence() = runTest {
        val source = List(25) { english }.joinToString(" ")
        val cache = Cache(); var calls = 0
        val provider = object : TranslationProvider { override fun generate(prompt: ModelPrompt) = flow {
            calls++; emit(ProviderEvent.Complete(if (calls == 1) json(chinese) else "{}"))
        } }
        val done = CompletableDeferred<Unit>(); val config = TranslationConfig()
        TranslationQueue(backgroundScope, cache) { provider }.submit(TranslationWork("bad-fragment", 0, 0,
            listOf(SourceSentence("long", source, "g")), config,
            committed = { _, value, _ -> assertEquals("$chinese *****（invalid_translation_json）", value) },
            finished = { failures, cancelled -> assertFalse(cancelled); assertTrue(failures.isEmpty()); done.complete(Unit) }))
        done.await(); assertEquals(2, calls); assertEquals(2, cache.writes.size)
        assertEquals("$chinese *****（invalid_translation_json）", cache.values[contentKey(source, config)])
    }

    @Test fun languageNeutralGoodCachesDoNotNeedInference() = runTest {
        val config = TranslationConfig()
        val sources = listOf("${'$'}E=mc^2${'$'}", "https://example.org/paper/123", "4 GB [12]", "`cache.get(key)`", "Qwen3:1.7b", "Albert Einstein")
        val cache = Cache(sources.associate { contentKey(it, config) to it }.toMutableMap())
        val committed = mutableListOf<String>(); val done = CompletableDeferred<Unit>()
        TranslationQueue(backgroundScope, cache) { fail("Good neutral caches must not request inference") }.submit(
            TranslationWork("neutral", 0, 0, sources.mapIndexed { i, text -> SourceSentence("s$i", text, "g") }, config,
                committed = { source, value, _ -> assertEquals(source.text, value); committed += value },
                finished = { failures, cancelled -> assertFalse(cancelled); assertTrue(failures.isEmpty()); done.complete(Unit) }))
        done.await(); assertEquals(sources, committed); assertTrue(cache.writes.isEmpty())
    }

    @Test fun queueUsesWorkConfigInsteadOfDefaultChineseTarget() = runTest {
        val config = TranslationConfig(targetLanguage = "en"); val cache = Cache(); var calls = 0
        val provider = object : TranslationProvider { override fun generate(prompt: ModelPrompt) = flow { calls++; emit(ProviderEvent.Complete(json(english))) } }
        val done = CompletableDeferred<Unit>()
        TranslationQueue(backgroundScope, cache) { provider }.submit(TranslationWork("english", 0, 0,
            listOf(SourceSentence("en", english, "g")), config,
            committed = { _, value, _ -> assertEquals(english, value) },
            finished = { failures, cancelled -> assertTrue(failures.isEmpty()); assertFalse(cancelled); done.complete(Unit) }))
        done.await(); assertEquals(1, calls); assertEquals(english, cache.values[contentKey(english, config)])
    }
}
