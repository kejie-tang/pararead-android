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
    private val warning = "（host_timeout）"
    private fun json(value: String) = JSONObject().put("translations", JSONArray().put(JSONObject().put("id", "s1").put("translation", value))).toString()

    private class Cache(val values: MutableMap<String, String> = mutableMapOf()) : TranslationCache {
        val writes = mutableListOf<String>()
        override suspend fun get(key: String) = values[key]
        override suspend fun put(key: String, text: String, translation: String, config: TranslationConfig) {
            values[key] = translation; writes += key
        }
    }

    @Test fun copiedEnglishIsKeptWithoutWarningAfterOneGenerationAndReopensWithoutInference() = runTest {
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
                    committed = { _, value, _ -> assertEquals(english, value); assertEquals(value, cache.values[key]) },
                    finished = { failures, cancelled -> assertFalse(cancelled); assertTrue(failures.isEmpty()); done.complete(Unit) }))
                done.await()
            }
            assertEquals(1, calls); assertEquals(listOf(key), cache.writes)
        }
    }

    @Test fun legacyMarkedCacheReopensWithoutQualityCodesInferenceOrRewritingStorage() = runTest {
        val config = TranslationConfig()
        val fingerprint = config.fingerprint()
        val key = translationCacheKeys(english, config).single { it.legacy }.key
        for ((legacy, displayed) in listOf(
            "$english *****（translation_language_or_source_copy）" to english,
            "$english（protected_token_mismatch, translation_language_or_source_copy）" to english,
            "$english *****（protected_token_mismatch, host_timeout）（translation_language_or_source_copy）" to english + warning)) {
            val cache = Cache(mutableMapOf(key to legacy))
            val queue = TranslationQueue(backgroundScope, cache) { fail("Legacy cache must not regenerate") }
            repeat(2) { n ->
                val done = CompletableDeferred<Unit>()
                queue.submit(TranslationWork("legacy-cache-$n", 0, 0, listOf(SourceSentence("s1", english, "g")), config,
                    committed = { _, value, cachedKey -> assertEquals(displayed, value); assertEquals(key, cachedKey) },
                    finished = { failures, cancelled -> assertTrue(failures.isEmpty()); assertFalse(cancelled); done.complete(Unit) }))
                done.await()
            }
            assertEquals(mapOf(key to legacy), cache.values)
            assertFalse(cache.values.containsKey(contentKey(english, config)))
            assertTrue(cache.writes.isEmpty())
        }
        assertEquals(fingerprint, config.fingerprint())
    }

    @Test fun currentCacheWinsOverLegacyWithoutInferenceOrWrites() = runTest {
        val config = TranslationConfig()
        val currentKey = contentKey(english, config)
        val legacyKey = translationCacheKeys(english, config).single { it.legacy }.key
        val stored = mapOf(currentKey to "当前译文。", legacyKey to "历史译文。（protected_token_mismatch）")
        val cache = Cache(stored.toMutableMap())
        val done = CompletableDeferred<Unit>()
        TranslationQueue(backgroundScope, cache) { fail("Current cache must win without inference") }.submit(
            TranslationWork("current-first", 0, 0, listOf(SourceSentence("s", english, "g")), config,
                committed = { _, value, key -> assertEquals("当前译文。", value); assertEquals(currentKey, key) },
                finished = { failures, cancelled -> assertTrue(failures.isEmpty()); assertFalse(cancelled); done.complete(Unit) }))
        done.await()
        assertEquals(stored, cache.values)
        assertTrue(cache.writes.isEmpty())
    }

    @Test fun emptyCurrentCacheFallsBackToLegacyWithoutPromotion() = runTest {
        val config = TranslationConfig()
        val currentKey = contentKey(english, config)
        val legacyKey = translationCacheKeys(english, config).single { it.legacy }.key
        val stored = mapOf(currentKey to "（http_500）", legacyKey to "$english（translation_language_or_source_copy, host_timeout）")
        val cache = Cache(stored.toMutableMap())
        val done = CompletableDeferred<Unit>()
        TranslationQueue(backgroundScope, cache) { fail("Usable legacy body must not regenerate") }.submit(
            TranslationWork("legacy-fallback", 0, 0, listOf(SourceSentence("s", english, "g")), config,
                committed = { _, value, key -> assertEquals(english + warning, value); assertEquals(legacyKey, key) },
                finished = { failures, cancelled -> assertTrue(failures.isEmpty()); assertFalse(cancelled); done.complete(Unit) }))
        done.await()
        assertEquals(stored, cache.values)
        assertTrue(cache.writes.isEmpty())
    }

    @Test fun legacyResidualPlaceholdersCauseOneGenerationWithoutDeletingOldCache() = runTest {
        val config = TranslationConfig()
        val currentKey = contentKey(english, config)
        val legacyKey = translationCacheKeys(english, config).single { it.legacy }.key
        val legacy = "残留 __PR0__（protected_token_mismatch）"
        val cache = Cache(mutableMapOf(legacyKey to legacy))
        var calls = 0
        val provider = object : TranslationProvider { override fun generate(prompt: ModelPrompt) = flow {
            calls++; emit(ProviderEvent.Complete(json(english)))
        } }
        val queue = TranslationQueue(backgroundScope, cache) { provider }
        repeat(2) { n ->
            val done = CompletableDeferred<Unit>()
            queue.submit(TranslationWork("residual-$n", 0, 0, listOf(SourceSentence("s", english, "g")), config,
                committed = { _, value, key -> assertEquals(english, value); assertEquals(currentKey, key); assertEquals(value, cache.values[key]) },
                finished = { failures, cancelled -> assertTrue(failures.isEmpty()); assertFalse(cancelled); done.complete(Unit) }))
            done.await()
        }
        assertEquals(1, calls)
        assertEquals(listOf(currentKey), cache.writes)
        assertEquals(legacy, cache.values[legacyKey])
    }

    @Test fun currentLiteralPlaceholderCacheIsReusedWithoutInference() = runTest {
        val source = "A literal __PR0__ is ordinary text."
        val config = TranslationConfig()
        val key = contentKey(source, config)
        val cache = Cache(mutableMapOf(key to "字面量 __PR0__。"))
        val done = CompletableDeferred<Unit>()
        TranslationQueue(backgroundScope, cache) { fail("Current literal content must remain reusable") }.submit(
            TranslationWork("literal-cache", 0, 0, listOf(SourceSentence("s", source, "g")), config,
                committed = { _, value, cachedKey -> assertEquals("字面量 __PR0__。", value); assertEquals(key, cachedKey) },
                finished = { failures, cancelled -> assertTrue(failures.isEmpty()); assertFalse(cancelled); done.complete(Unit) }))
        done.await()
        assertEquals(mapOf(key to "字面量 __PR0__。"), cache.values)
        assertTrue(cache.writes.isEmpty())
    }

    @Test fun cachesFromOtherConfigurationsDoNotPreventGeneration() = runTest {
        val config = TranslationConfig()
        for (other in listOf(config.copy(provider = "api"), config.copy(model = "other-model"),
            config.copy(modelHash = "other-hash"), config.copy(sourceLanguage = "en"),
            config.copy(targetLanguage = "ja"), config.copy(glossary = """{"attention":"注意"}"""))) {
            val stored = translationCacheKeys(english, other).associate { it.key to "其他配置的译文。" }
            val cache = Cache(stored.toMutableMap())
            var calls = 0
            val provider = object : TranslationProvider { override fun generate(prompt: ModelPrompt) = flow {
                calls++; emit(ProviderEvent.Complete(json(chinese)))
            } }
            val done = CompletableDeferred<Unit>()
            val key = contentKey(english, config)
            TranslationQueue(backgroundScope, cache) { provider }.submit(
                TranslationWork("config-fence", 0, 0, listOf(SourceSentence("s", english, "g")), config,
                    committed = { _, value, cachedKey -> assertEquals(chinese, value); assertEquals(key, cachedKey) },
                    finished = { failures, cancelled -> assertTrue(failures.isEmpty()); assertFalse(cancelled); done.complete(Unit) }))
            done.await()
            assertEquals(1, calls)
            assertEquals(listOf(key), cache.writes)
            assertEquals(stored + (key to chinese), cache.values)
        }
    }

    @Test fun unmarkedPoorCacheAndGoodCacheAreReusedWithoutInferenceOrFingerprintChanges() = runTest {
        val config = TranslationConfig()
        val bad = SourceSentence("bad", english, "g")
        val good = SourceSentence("good", "The model retains the previous observations.", "g")
        val badKey = contentKey(bad.text, config); val goodKey = contentKey(good.text, config)
        val fingerprint = config.fingerprint()
        val cache = Cache(mutableMapOf(badKey to "中译 $english", goodKey to "该模型保留先前的观测。"))
        val commits = mutableListOf<String>()
        val queue = TranslationQueue(backgroundScope, cache) { fail("Nonempty caches must not regenerate based on quality") }
        suspend fun open(id: String) {
            val done = CompletableDeferred<Unit>()
            assertTrue(queue.submit(TranslationWork(id, 0, 0, listOf(bad, good), config,
                committed = { source, value, key -> assertEquals(value, cache.values[key]); commits += source.id },
                finished = { failures, cancelled -> assertFalse(cancelled); assertTrue(failures.isEmpty()); done.complete(Unit) })))
            done.await()
        }
        open("first")
        assertEquals(listOf("bad", "good"), commits)
        assertEquals("中译 $english", cache.values[badKey])
        assertEquals("该模型保留先前的观测。", cache.values[goodKey])
        open("reopen")
        assertEquals(listOf("bad", "good", "bad", "good"), commits)
        assertTrue(cache.writes.isEmpty())
        assertEquals(2, cache.values.size); assertEquals(fingerprint, config.fingerprint())
    }

    @Test fun longSentenceUsesEachMissingFragmentOnceAndConsolidatesRuntimeWarningsAtTheEnd() = runTest {
        val config = TranslationConfig()
        val source = List(25) { "$it: $english" }.joinToString(" ")
        val sentence = SourceSentence("long", source, "g")
        val parts = StructuredTranslation.fragments(source)
        assertTrue(parts.size >= 3)
        val originalKey = contentKey(source, config)
        val firstKey = contentKey(parts.first(), config)
        val firstTranslation = parts.first().trim()
        val cachedTranslation = parts[1].trim()
        val cache = Cache()
        // A copied fragment with a runtime error and translated neighbours are reusable; only the first is missing.
        cache.values[contentKey(parts[1], config)] = cachedTranslation + warning
        parts.drop(2).forEach { cache.values[contentKey(it, config)] = chinese }
        var calls = 0
        val provider = object : TranslationProvider { override fun generate(prompt: ModelPrompt) = flow {
            calls++; emit(ProviderEvent.Complete(json(firstTranslation)))
        } }
        val queue = TranslationQueue(backgroundScope, cache) { provider }
        repeat(2) { n ->
            val done = CompletableDeferred<Unit>()
            queue.submit(TranslationWork("fragments-$n", 0, 0, listOf(sentence), config,
                committed = { _, value, key ->
                    assertEquals(firstTranslation + cachedTranslation + chinese.repeat(parts.size - 2) + warning, value)
                    assertEquals(originalKey, key)
                    assertEquals(1, Regex("host_timeout").findAll(value).count())
                }, finished = { failures, cancelled -> assertFalse(cancelled); assertTrue(failures.isEmpty()); done.complete(Unit) }))
            done.await()
        }
        assertEquals(1, calls); assertEquals(listOf(firstKey, originalKey), cache.writes)
        parts.drop(2).forEach { assertEquals(chinese, cache.values[contentKey(it, config)]) }
    }

    @Test fun legacyFragmentsAreReusedWithoutInferenceOrFragmentPromotion() = runTest {
        val config = TranslationConfig()
        val source = List(25) { "$it: $english" }.joinToString(" ")
        val parts = StructuredTranslation.fragments(source)
        assertTrue(parts.size > 1)
        val stored = parts.mapIndexed { i, part ->
            translationCacheKeys(part, config).single { it.legacy }.key to
                "片$i *****（protected_token_mismatch, translation_language_or_source_copy, host_timeout）"
        }.toMap()
        val cache = Cache(stored.toMutableMap())
        val originalKey = contentKey(source, config)
        val expected = parts.indices.joinToString("") { "片$it" } + warning
        val done = CompletableDeferred<Unit>()
        TranslationQueue(backgroundScope, cache) { fail("Legacy fragments must not regenerate") }.submit(
            TranslationWork("legacy-fragments", 0, 0, listOf(SourceSentence("long", source, "g")), config,
                committed = { _, value, key ->
                    assertEquals(expected, value); assertEquals(originalKey, key); assertEquals(value, cache.values[key])
                }, finished = { failures, cancelled -> assertTrue(failures.isEmpty()); assertFalse(cancelled); done.complete(Unit) }))
        done.await()
        assertEquals(listOf(originalKey), cache.writes)
        assertEquals(stored + (originalKey to expected), cache.values)
        parts.forEach { assertFalse(cache.values.containsKey(contentKey(it, config))) }
    }

    @Test fun failedFragmentKeepsCompletedTextAndDoesNotRegenerateTheWholeSentence() = runTest {
        val source = List(25) { "$it: $english" }.joinToString(" ")
        val cache = Cache(); var calls = 0
        val provider = object : TranslationProvider { override fun generate(prompt: ModelPrompt) = flow {
            calls++; emit(ProviderEvent.Complete(if (calls == 1) json(chinese) else "{}"))
        } }
        val done = CompletableDeferred<Unit>(); val config = TranslationConfig()
        TranslationQueue(backgroundScope, cache) { provider }.submit(TranslationWork("bad-fragment", 0, 0,
            listOf(SourceSentence("long", source, "g")), config,
            committed = { _, value, _ -> assertEquals("$chinese（invalid_translation_json）", value) },
            finished = { failures, cancelled -> assertFalse(cancelled); assertTrue(failures.isEmpty()); done.complete(Unit) }))
        done.await(); assertEquals(2, calls); assertEquals(2, cache.writes.size)
        assertEquals("$chinese（invalid_translation_json）", cache.values[contentKey(source, config)])
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
