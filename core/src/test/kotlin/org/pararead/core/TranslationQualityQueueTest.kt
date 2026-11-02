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
    private fun json(value: String) = JSONObject().put("translations", JSONArray().put(JSONObject().put("id", "s1").put("translation", value))).toString()

    private class Cache(val values: MutableMap<String, String> = mutableMapOf()) : TranslationCache {
        val writes = mutableListOf<String>()
        override suspend fun get(key: String) = values[key]
        override suspend fun put(key: String, text: String, translation: String, config: TranslationConfig) {
            values[key] = translation
            writes += key
        }
    }

    @Test fun copiedEnglishExhaustsFiniteRecoveryWithoutCachingOrCommitting() = runTest {
        for (target in listOf("zh-CN", "zh-TW")) {
            val config = TranslationConfig(targetLanguage = target)
            val key = contentKey(english, config)
            val cache = Cache(mutableMapOf(key to english))
            val formats = mutableListOf<ModelOutputFormat>()
            val provider = object : TranslationProvider {
                override fun generate(prompt: ModelPrompt) = flow {
                    formats += prompt.outputFormat
                    val value = if (formats.size == 2) "译 $english" else english
                    emit(ProviderEvent.Complete(if (prompt.outputFormat == ModelOutputFormat.TEXT) value else json(value)))
                }
            }
            val done = CompletableDeferred<Unit>()
            TranslationQueue(backgroundScope, cache) { provider }.submit(TranslationWork("exhaust-$target", 0, 0,
                listOf(SourceSentence("bad", english, "g")), config,
                committed = { _, _, _ -> fail("Copied English must not be committed") },
                finished = { failures, cancelled ->
                    assertFalse(cancelled)
                    assertEquals(listOf("bad" to "TRANSLATION_LANGUAGE_OR_SOURCE_COPY"), failures)
                    done.complete(Unit)
                }))
            done.await()
            assertEquals(listOf(ModelOutputFormat.TRANSLATION_JSON, ModelOutputFormat.TRANSLATION_JSON, ModelOutputFormat.TEXT), formats)
            assertTrue(cache.writes.isEmpty())
            assertEquals(english, cache.values[key]) // No destructive cache clearing on failure.
        }
    }

    @Test fun badSentenceCacheIsRepairedButGoodCacheAndFingerprintAreRetained() = runTest {
        val config = TranslationConfig()
        val bad = SourceSentence("bad", english, "g")
        val good = SourceSentence("good", "The model retains the previous observations.", "g")
        val badKey = contentKey(bad.text, config)
        val goodKey = contentKey(good.text, config)
        val cache = Cache(mutableMapOf(badKey to "中译 $english", goodKey to "该模型保留先前的观测。"))
        val prompts = mutableListOf<ModelPrompt>()
        val commits = mutableListOf<String>()
        val provider = object : TranslationProvider {
            override fun generate(prompt: ModelPrompt) = flow {
                prompts += prompt
                assertFalse(prompt.user.contains(good.text))
                val value = when (prompts.size) { 1 -> english; 2 -> "中译 $english"; else -> chinese }
                emit(ProviderEvent.Complete(if (prompt.outputFormat == ModelOutputFormat.TEXT) value else json(value)))
            }
        }
        val queue = TranslationQueue(backgroundScope, cache) { provider }
        suspend fun open(id: String) {
            val done = CompletableDeferred<Unit>()
            assertTrue(queue.submit(TranslationWork(id, 0, 0, listOf(bad, good), config,
                committed = { source, value, key -> assertEquals(value, cache.values[key]); commits += source.id },
                finished = { failures, cancelled -> assertFalse(cancelled); assertTrue(failures.isEmpty()); done.complete(Unit) })))
            done.await()
        }
        open("repair")
        assertEquals(listOf("good", "bad"), commits)
        assertEquals(chinese, cache.values[badKey])
        assertEquals("该模型保留先前的观测。", cache.values[goodKey])
        assertEquals(listOf(badKey), cache.writes)
        assertEquals(3, prompts.size)
        open("reopen")
        assertEquals(3, prompts.size)
        assertEquals(listOf(badKey), cache.writes)
        assertEquals(2, cache.values.size)
        assertTrue(config.fingerprint().contains("validator=6"))
    }

    @Test fun badLongSentenceAndFragmentCachesMissWhileGoodFragmentsSurviveRetry() = runTest {
        val config = TranslationConfig()
        val source = List(14) { english }.joinToString(" ")
        val sentence = SourceSentence("long", source, "g")
        val parts = StructuredTranslation.fragments(source)
        assertTrue(parts.size > 1)
        val originalKey = contentKey(source, config)
        val firstKey = contentKey(parts.first(), config)
        val cache = Cache(mutableMapOf(originalKey to source, firstKey to parts.first()))
        parts.drop(1).forEach { cache.values[contentKey(it, config)] = "后续片段的合格译文。" }
        val prompts = mutableListOf<ModelPrompt>()
        val provider = object : TranslationProvider {
            override fun generate(prompt: ModelPrompt) = flow {
                prompts += prompt
                emit(ProviderEvent.Complete(json(if (prompts.size == 1) parts.first() else chinese)))
            }
        }
        val done = CompletableDeferred<Unit>()
        TranslationQueue(backgroundScope, cache) { provider }.submit(TranslationWork("fragments", 0, 0, listOf(sentence), config,
            committed = { _, value, key ->
                assertEquals(chinese + "后续片段的合格译文。".repeat(parts.size - 1), value)
                assertEquals(originalKey, key)
            }, finished = { failures, cancelled -> assertFalse(cancelled); assertTrue(failures.isEmpty()); done.complete(Unit) }))
        done.await()
        assertEquals(2, prompts.size)
        assertEquals(listOf(firstKey, originalKey), cache.writes)
        assertEquals(chinese, cache.values[firstKey])
        parts.drop(1).forEach { assertEquals("后续片段的合格译文。", cache.values[contentKey(it, config)]) }
    }

    @Test fun languageNeutralGoodCachesDoNotNeedInference() = runTest {
        val config = TranslationConfig()
        val sources = listOf("${'$'}E=mc^2${'$'}", "https://example.org/paper/123", "4 GB [12]", "`cache.get(key)`", "Qwen3:1.7b", "Albert Einstein")
        val cache = Cache(sources.associate { contentKey(it, config) to it }.toMutableMap())
        val committed = mutableListOf<String>()
        val done = CompletableDeferred<Unit>()
        TranslationQueue(backgroundScope, cache) { fail("Good neutral caches must not request inference") }.submit(
            TranslationWork("neutral", 0, 0, sources.mapIndexed { i, text -> SourceSentence("s$i", text, "g") }, config,
                committed = { source, value, _ -> assertEquals(source.text, value); committed += value },
                finished = { failures, cancelled -> assertFalse(cancelled); assertTrue(failures.isEmpty()); done.complete(Unit) }))
        done.await()
        assertEquals(sources, committed)
        assertTrue(cache.writes.isEmpty())
    }

    @Test fun queueUsesWorkConfigInsteadOfDefaultChineseTarget() = runTest {
        val config = TranslationConfig(targetLanguage = "en")
        val cache = Cache()
        var calls = 0
        val provider = object : TranslationProvider {
            override fun generate(prompt: ModelPrompt) = flow { calls++; emit(ProviderEvent.Complete(json(english))) }
        }
        val done = CompletableDeferred<Unit>()
        TranslationQueue(backgroundScope, cache) { provider }.submit(TranslationWork("english", 0, 0,
            listOf(SourceSentence("en", english, "g")), config,
            committed = { _, value, _ -> assertEquals(english, value) },
            finished = { failures, cancelled -> assertTrue(failures.isEmpty()); assertFalse(cancelled); done.complete(Unit) }))
        done.await()
        assertEquals(1, calls)
        assertEquals(english, cache.values[contentKey(english, config)])
    }
}
