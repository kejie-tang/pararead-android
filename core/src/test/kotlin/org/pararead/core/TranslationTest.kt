package org.pararead.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import kotlin.test.*
import org.junit.Test

class TranslationTest {
    @Test fun backendRestartClearsGpuDraftAndOnlyCommitsCpuResult() = runTest {
        val stored = mutableListOf<String>(); val drafts = mutableListOf<String>()
        val cache = object : TranslationCache {
            override suspend fun get(key: String): String? = null
            override suspend fun put(key: String, text: String, translation: String, config: TranslationConfig) { stored += translation }
        }
        val correct = "{\"translations\":[{\"id\":\"s1\",\"translation\":\"正确译文\"}]}"
        val provider = object : TranslationProvider { override fun generate(prompt: ModelPrompt) = flow {
            emit(ProviderEvent.Delta("{\"translations\":[{\"id\":\"s1\",\"translation\":\"错误草稿"))
            emit(ProviderEvent.Restart)
            emit(ProviderEvent.Delta(correct)); emit(ProviderEvent.Complete(correct))
        } }
        val done = CompletableDeferred<Unit>()
        TranslationQueue(backgroundScope, cache) { provider }.submit(TranslationWork("restart", 0, 0,
            listOf(SourceSentence("one", "A model.", "g")), TranslationConfig(),
            committed = { _, value, _ -> assertEquals("正确译文", value) },
            finished = { failures, cancelled -> assertTrue(failures.isEmpty()); assertFalse(cancelled); done.complete(Unit) },
            delta = { _, value -> drafts += value }))
        done.await()
        assertEquals(listOf("正确译文"), stored)
        assertEquals(listOf("错误草稿", "", "正确译文"), drafts)
    }
    @Test fun batchInputsKeepOriginalTextAndDraftsBelongOnlyToTheirIds() {
        val sentences = listOf(SourceSentence("first", "Use 4 GB [12].", "g"), SourceSentence("second", "Preserve `cache.get(key)`.", "g"))
        val (prompt, inputs) = StructuredTranslation.prompt(sentences, TranslationConfig())
        val rows = JSONObject(prompt.user.substringBefore("\n/no_think")).getJSONArray("sentences")
        assertEquals(sentences.map { it.text }, inputs.map { it.text })
        sentences.forEachIndexed { i, sentence ->
            assertEquals("s${i + 1}", rows.getJSONObject(i).getString("id"))
            assertEquals(sentence.text, rows.getJSONObject(i).getString("text"))
        }
        assertFalse(prompt.user.contains("__PR"))
        val draft = StructuredTranslation.drafts("{\"id\":\"s2\",\"translation\":\"保留 `cache.get(key)`。", sentences, inputs)
        assertEquals(listOf(sentences[1] to "保留 `cache.get(key)`。"), draft)
        val recovered = StructuredTranslation.salvage("{\"id\":\"s2\",\"translation\":\"保留代码。", sentences, inputs,
            TranslationConfig(), "TRUNCATED_STREAM_length")
        assertEquals(listOf(sentences[1] to "保留代码。（truncated_stream_length）"), recovered.accepted)
        assertEquals(listOf(sentences[0] to "TRUNCATED_STREAM_length"), recovered.rejected)
        for (unsafe in listOf("{\"id\":\"s3\",\"translation\":\"未知归属", "{\"translations\":[",
            "<think>推理尚未结束", "{\"translation\":\"没有 ID\"}",
            "[{\"id\":\"s1\",\"translation\":\"甲\"},{\"id\":\"s1\",\"translation\":\"乙\"}]")) {
            assertTrue(StructuredTranslation.drafts(unsafe, sentences, inputs).isEmpty(), unsafe)
        }
    }
    @Test fun trailingOutputCannotHideBehindAValidJsonPrefix() {
        val s = listOf(SourceSentence("p1-b1-s1", "One.", "p1-b1"))
        val (_, inputs) = StructuredTranslation.prompt(s, TranslationConfig())
        val valid = "{\"translations\":[{\"id\":\"s1\",\"translation\":\"一。\"}]}"
        assertEquals("一。", StructuredTranslation.validate(valid + "\n ", s, inputs).single().second)
        for (tail in listOf("{}", "\n{\"glossary\":{}}", " trailing prose", "[]")) {
            assertFails { StructuredTranslation.validate(valid + tail, s, inputs) }
        }
    }
    @Test fun exactIdsAreRequiredButNumbersAndReferencesAreNotValidated() {
        val s = listOf(SourceSentence("p1-b0-s0", "Use 10 kg and $" + "x+y$ [12] https://a.example/x", "p1-b0"))
        val (prompt, inputs) = StructuredTranslation.prompt(s, TranslationConfig())
        assertEquals(s.single().text, inputs.single().text)
        assertEquals(s.single().text, JSONObject(prompt.user.substringBefore("\n/no_think"))
            .getJSONArray("sentences").getJSONObject(0).getString("text"))
        for (translated in listOf("使用 10 kg 和 ${'$'}x+y${'$'} [12] https://a.example/x", "使用 9 kg。", "无关译文。")) {
            val result = JSONObject().put("translations", JSONArray().put(JSONObject().put("id", "s1").put("translation", translated))).toString()
            assertEquals(translated, StructuredTranslation.validate(result, s, inputs).single().second)
            assertFails { StructuredTranslation.validate(result.replace("s1", "s2"), s, inputs) }
        }
        assertFails { StructuredTranslation.validate("{\"translations\":[]}", s, inputs) }
    }
    @Test fun promptsUseOriginalTextAndOnlyTranslationFormatInstructions() {
        val source = "  Use 4 GB [12], ${'$'}x+y${'$'}, https://a.example/x and `cache.get(key)`.\n```python\nprint(42)\n```\n中文😀 e\u0301  "
        val sentence = SourceSentence("a", source, "g")
        for (target in listOf("zh-CN", "en")) {
            val config = TranslationConfig(sourceLanguage = "en", targetLanguage = target)
            val (batchPrompt, inputs) = StructuredTranslation.prompt(listOf(sentence), config)
            val (textPrompt, input) = StructuredTranslation.textPrompt(sentence, config)
            assertEquals(source, inputs.single().text)
            assertEquals(source, input.text)
            assertEquals(source, JSONObject(batchPrompt.user.substringBefore("\n/no_think"))
                .getJSONArray("sentences").getJSONObject(0).getString("text"))
            assertEquals(source + "\n/no_think", textPrompt.user)
            assertEquals(1, batchPrompt.expectedResults)
            assertEquals(ModelOutputFormat.TRANSLATION_JSON, batchPrompt.outputFormat)
            assertEquals(ModelOutputFormat.TEXT, textPrompt.outputFormat)
            for (prompt in listOf(batchPrompt, textPrompt)) {
                assertFalse(prompt.system.contains("\"translation\":\"...\""))
                for (removed in listOf("__PR", "protected_token_mismatch", "下划线", "negation", "contrast", "否定", "转折", "不要总结")) {
                    assertFalse(prompt.system.contains(removed, ignoreCase = true), removed)
                }
            }
            if (target == "en") {
                assertTrue(batchPrompt.system.contains("to en"))
                assertTrue(textPrompt.system.contains("to en"))
            }
        }
    }
    @Test fun literalPlaceholderLikeTextIsOrdinarySourceAndOutput() {
        val sentence = SourceSentence("literal", "A literal __PR0__ is ordinary text.", "g")
        val config = TranslationConfig()
        val (prompt, input) = StructuredTranslation.textPrompt(sentence, config)
        assertEquals(sentence.text, input.text)
        assertEquals(sentence.text + "\n/no_think", prompt.user)
        assertEquals("原样 __PR0__。", StructuredTranslation.validateText("原样 __PR0__。", input, config))
    }
    @Test fun duplicateIdsCannotOverwrite() {
        val s = listOf(SourceSentence("p1-b0-s0", "One.", "g"), SourceSentence("p1-b0-s1", "Two.", "g"))
        val (_, inputs) = StructuredTranslation.prompt(s, TranslationConfig())
        assertFails { StructuredTranslation.validate("[{\"id\":\"s1\",\"translation\":\"一\"},{\"id\":\"s1\",\"translation\":\"二\"}]", s, inputs) }
    }
    @Test fun invalidOrUnfinishedOutputNeverValidates() {
        val s = listOf(SourceSentence("p1-b0-s0", "Equation $" + "x=2$.", "p1-b0"))
        val (_, inputs) = StructuredTranslation.prompt(s, TranslationConfig())
        for (raw in listOf("{", "{\"translations\":[]}", "[{\"id\":\"s1\",\"translation\":\"\"}]", "<think>reason</think>[]")) {
            assertFails(raw) { StructuredTranslation.validate(raw, s, inputs) }
        }
    }
    @Test fun draftsDecodeJsonEscapesWithoutChangingModelText() {
        val s = listOf(SourceSentence("p1-b0-s0", "Read 10 kg.", "g"))
        val (_, inputs) = StructuredTranslation.prompt(s, TranslationConfig())
        val draft = StructuredTranslation.drafts("{\"translations\":[{\"id\":\"s1\",\"translation\":\"译文 9 kg \\\"quoted\\\"", s, inputs)
        assertEquals(listOf(s.single() to "译文 9 kg \"quoted\""), draft)
    }
    @Test fun fragmentsRetainOriginalTextAndNeverSplitUnicodeSurrogatePairs() {
        val original = "a".repeat(1199) + "😀" + "${'$'}x+y=42${'$'} [12]\r\n中文 e\u0301 `code` ".repeat(150)
        for (limit in listOf(2, 17, 1200)) {
            val fragments = StructuredTranslation.fragments(original, limit)
            assertTrue(fragments.size > 1)
            assertEquals(original, fragments.joinToString(""))
            fragments.forEach { part ->
                assertTrue(part.isNotEmpty())
                assertTrue(part.length <= limit)
                assertFalse(part.first().isLowSurrogate())
                assertFalse(part.last().isHighSurrogate())
            }
        }
    }
    @Test fun invalidOutputWithoutUsableTextFailsOnceAndNeverEntersCache() = runTest {
        var calls = 0
        val cache = object : TranslationCache { override suspend fun get(key: String): String? = null; override suspend fun put(key: String, text: String, translation: String, config: TranslationConfig) = fail("Invalid output reached cache") }
        val provider = object : TranslationProvider { override fun generate(prompt: ModelPrompt) = flow { calls++; emit(ProviderEvent.Delta("{\"translations\":[{\"id\":\"s1\",\"translation\":\"")); emit(ProviderEvent.Complete("{}")) } }
        val done = CompletableDeferred<Unit>(); var drafts = 0
        TranslationQueue(backgroundScope, cache) { provider }.submit(TranslationWork("bad", 0, 0, listOf(SourceSentence("p1-b0-s0", "One.", "g")), TranslationConfig(),
            committed = { _,_,_ -> fail() }, finished = { failures, cancelled -> assertEquals(1, failures.size); assertFalse(cancelled); done.complete(Unit) }, delta = { _, _ -> drafts++ }))
        done.await(); assertEquals(1, calls); assertEquals(0, drafts)
    }
    @Test fun cacheIdentityIncludesModelLanguageAndGlossary() {
        val c = TranslationConfig()
        assertEquals(contentKey("café   A", c), contentKey("cafe\u0301 A", c))
        assertNotEquals(contentKey("x", c), contentKey("x", c.copy(modelHash = "different")))
        assertNotEquals(contentKey("x", c), contentKey("x", c.copy(sourceLanguage = "en")))
        assertEquals(contentKey("x", c.copy(glossary = "{\"b\":\"B\",\"a\":\"A\"}")), contentKey("x", c.copy(glossary = "{\"a\":\"A\",\"b\":\"B\"}")))
    }
    @Test fun cacheCandidatesPreferCurrentFingerprintAndOnlyFallBackToSameConfigSixSix() {
        val config = TranslationConfig()
        val fingerprintParts = config.fingerprint().split('\u0000')
        assertTrue("prompt=7" in fingerprintParts)
        assertTrue("validator=7" in fingerprintParts)
        val legacyFingerprint = fingerprintParts.joinToString("\u0000") {
            when (it) { "prompt=7" -> "prompt=6"; "validator=7" -> "validator=6"; else -> it }
        }
        val candidates = translationCacheKeys("x", config)
        assertEquals(2, candidates.size)
        assertEquals(contentKey("x", config), candidates[0].key)
        assertFalse(candidates[0].legacy)
        assertEquals(sha256("x\u0000$legacyFingerprint"), candidates[1].key)
        assertTrue(candidates[1].legacy)
        assertEquals(translationCacheKeys("café   A", config), translationCacheKeys("cafe\u0301 A", config))
        assertEquals(translationCacheKeys("x", config.copy(glossary = "{\"b\":\"B\",\"a\":\"A\"}")),
            translationCacheKeys("x", config.copy(glossary = "{\"a\":\"A\",\"b\":\"B\"}")))
        val keys = candidates.map { it.key }.toSet()
        for (other in listOf(config.copy(provider = "api"), config.copy(model = "other-model"),
            config.copy(modelHash = "other-hash"), config.copy(sourceLanguage = "en"),
            config.copy(targetLanguage = "ja"), config.copy(glossary = "{\"x\":\"译\"}"))) {
            assertTrue(translationCacheKeys("x", other).none { it.key in keys })
        }
    }
    @Test fun protocolRejectsBadVersionAndUtf8Limit() {
        val message = Envelope("reader.ready", "s", "d", "r", "q", 0, 0, JSONObject())
        assertEquals("reader.ready", Envelope.parse(message.json().toString()).type)
        val lookup = message.copy(type = "translation.cacheGet")
        assertEquals("translation.cacheGet", Envelope.parse(lookup.json().toString()).type)
        assertFails { Envelope.parse(message.copy(type = "translation.unknown").json().toString()) }
        assertFails { Envelope.parse(message.json().put("version", 99).toString()) }
        assertFails { Envelope.parse(message.json().put("payload", JSONObject().put("text", "中".repeat(22000))).toString()) }
        assertFails { Envelope.parse("{}") }
    }
    @Test fun sseUtf8AndFinishValidation() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            val chunk = JSONObject().put("choices", JSONArray().put(JSONObject().put("delta", JSONObject().put("content", "中文😀")).put("finish_reason", JSONObject.NULL)))
            val stop = "{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}"
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setChunkedBody("data: $chunk\n\ndata: $stop\n\ndata: [DONE]\n\n", 1))
            val p = OpenAICompatibleProvider(server.url("/v1").toString(), "qwen")
            val events = p.generate(ModelPrompt("system", "user")).toList()
            assertEquals("中文😀", (events.last() as ProviderEvent.Complete).text)
            val requestBody = JSONObject(server.takeRequest().body.readUtf8())
            assertFalse(requestBody.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $chunk\n\ndata: [DONE]\n\n"))
            assertFails { p.generate(ModelPrompt("s", "u")).toList() }; Unit
        } finally { server.shutdown() }
    }
    @Test fun cacheCommitBeforeCallbackAndReopenSkipsProvider() = runTest {
        val values = mutableMapOf<String, String>(); var calls = 0
        val cache = object : TranslationCache {
            override suspend fun get(key: String) = values[key]
            override suspend fun put(key: String, text: String, translation: String, config: TranslationConfig) { values[key] = translation }
        }
        val provider = object : TranslationProvider { override fun generate(prompt: ModelPrompt) = flow { calls++; emitAll(MockTranslationProvider().generate(prompt)) } }
        val queue = TranslationQueue(backgroundScope, cache) { provider }
        repeat(2) { n ->
            val done = CompletableDeferred<Unit>()
            assertTrue(queue.submit(TranslationWork("r$n", 0, 0, listOf(SourceSentence("p1-b0-s0", "Test 10 kg.", "p1-b0")), TranslationConfig(),
                committed = { _, text, key -> assertEquals(text, values[key]) }, finished = { failures, cancelled -> assertTrue(failures.isEmpty()); assertFalse(cancelled); done.complete(Unit) })))
            done.await()
        }
        assertEquals(1, calls)
    }
    @Test fun cancellationSettlesAndSelectionPreempts() = runTest {
        val cache = object : TranslationCache { override suspend fun get(key: String): String? = null; override suspend fun put(key: String, text: String, translation: String, config: TranslationConfig) {} }
        val begun = CompletableDeferred<Unit>(); val stopped = CompletableDeferred<Unit>(); val done = CompletableDeferred<Unit>()
        val provider = object : TranslationProvider { override fun generate(prompt: ModelPrompt) = flow<ProviderEvent> { begun.complete(Unit); try { awaitCancellation() } finally { stopped.complete(Unit) } } }
        val queue = TranslationQueue(backgroundScope, cache) { provider }
        queue.submit(TranslationWork("old", 0, 2, listOf(SourceSentence("p1-b0-s0", "Test", "p1-b0")), TranslationConfig(), committed = { _,_,_ -> fail() }, finished = { _,cancelled -> assertTrue(cancelled); done.complete(Unit) }))
        begun.await(); queue.viewport(1); done.await(); stopped.await()
    }
    @Test fun selectionPreemptsAndWholeBookWorkResumesAfterwards() = runTest {
        val values = mutableMapOf<String,String>(); val order = mutableListOf<String>(); var calls = 0
        val cache = object : TranslationCache { override suspend fun get(key: String) = values[key]; override suspend fun put(key: String,text:String,translation:String,config:TranslationConfig) { values[key]=translation } }
        val begun = CompletableDeferred<Unit>(); val resumed = CompletableDeferred<Unit>(); val selected = CompletableDeferred<Unit>()
        val provider = object : TranslationProvider { override fun generate(prompt: ModelPrompt) = flow {
            calls++; if (calls == 1) { begun.complete(Unit); awaitCancellation() }
            emitAll(MockTranslationProvider().generate(prompt))
        } }
        val queue = TranslationQueue(backgroundScope,cache) { provider }
        queue.submit(TranslationWork("whole",0,3,listOf(SourceSentence("p2-b0-s0","Background.","g")),TranslationConfig(),
            committed={_,_,_->order+="whole"},finished={_,cancelled->assertFalse(cancelled);resumed.complete(Unit)}))
        begun.await()
        queue.submit(TranslationWork("selected",0,0,listOf(SourceSentence("p1-b0-s0","Selected.","g")),TranslationConfig(),
            committed={_,_,_->order+="selected"},finished={_,cancelled->assertFalse(cancelled);selected.complete(Unit)}))
        selected.await(); resumed.await(); assertEquals(listOf("selected","whole"),order); assertEquals(3,calls)
    }
}
