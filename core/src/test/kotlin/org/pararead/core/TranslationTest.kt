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
    @Test fun batchPlaceholdersCannotReferToAnotherSentence() {
        val sentences = listOf(SourceSentence("first", "Use 4 GB [12].", "g"), SourceSentence("second", "Preserve `cache.get(key)`.", "g"))
        val (prompt, values) = StructuredTranslation.prompt(sentences, TranslationConfig())
        assertTrue(prompt.user.contains("__PR2__"))
        assertEquals("保留 `cache.get(key)`。", values[1].restore("保留 __PR2__。"))
        assertFails { values[1].restore("保留 __PR0__。") }
        val draft = StructuredTranslation.drafts("{\"id\":\"s2\",\"translation\":\"保留 __PR2__。", sentences, values)
        assertEquals("保留 `cache.get(key)`。", draft.single().second)
    }
    @Test fun trailingOutputCannotHideBehindAValidJsonPrefix() {
        val s = listOf(SourceSentence("p1-b1-s1", "One.", "p1-b1"))
        val (_, protected) = StructuredTranslation.prompt(s, TranslationConfig())
        val valid = "{\"translations\":[{\"id\":\"s1\",\"translation\":\"一。\"}]}"
        assertEquals("一。", StructuredTranslation.validate(valid + "\n ", s, protected).single().second)
        for (tail in listOf("{}", "\n{\"glossary\":{}}", " trailing prose", "[]")) {
            assertFails { StructuredTranslation.validate(valid + tail, s, protected) }
        }
    }
    @Test fun exactIdsAndProtectedTokens() {
        val s = listOf(SourceSentence("p1-b0-s0", "Use 10 kg and $" + "x+y$ [12] https://a.example/x", "p1-b0"))
        val (prompt, protected) = StructuredTranslation.prompt(s, TranslationConfig())
        assertTrue(prompt.user.contains("__PR0__"))
        val translated = "使用 " + protected[0].text
        val result = JSONObject().put("translations", JSONArray().put(JSONObject().put("id", "s1").put("translation", translated))).toString()
        assertTrue(StructuredTranslation.validate(result, s, protected).single().second.contains("10 kg"))
        assertFails { StructuredTranslation.validate(result.replace("__PR0__", "10"), s, protected) }
        assertFails { StructuredTranslation.validate(result.replace("s1", "s2"), s, protected) }
        assertFails { StructuredTranslation.validate("{\"translations\":[]}", s, protected) }
    }
    @Test fun asciiMarkerProtocolRejectsMutationsAndReservedInput() {
        val protected = StructuredTranslation.protect("Use 4 GB [12].")
        assertEquals("Use __PR0__ __PR1__.", protected.text)
        assertEquals("使用 4 GB [12]。", protected.restore("使用 __PR0__ __PR1__。"))
        for (bad in listOf("使用 __PR0_ __PR1__。", "使用 __PR0__ __PR0__。", "使用 ⟦P0⟧ ⟦P1⟧。")) {
            assertFails { protected.restore(bad) }
        }
        assertFails { StructuredTranslation.protect("A literal __PR0__ is reserved.") }
        val (prompt, _) = StructuredTranslation.prompt(listOf(SourceSentence("a", "Use 4 GB.", "g")), TranslationConfig())
        assertFalse(prompt.system.contains("\"translation\":\"...\""))
        assertTrue(prompt.user.contains("__PR0__"))
        assertTrue(prompt.system.contains("下划线"))
        val english = StructuredTranslation.prompt(listOf(SourceSentence("a", "测试 4 GB。", "g")), TranslationConfig(targetLanguage = "en")).first
        assertTrue(english.system.contains("__PRnumber__"))
        assertTrue(english.system.contains("to en"))
    }
    @Test fun duplicateIdsCannotOverwrite() {
        val s = listOf(SourceSentence("p1-b0-s0", "One.", "g"), SourceSentence("p1-b0-s1", "Two.", "g"))
        val (_, protected) = StructuredTranslation.prompt(s, TranslationConfig())
        assertFails { StructuredTranslation.validate("[{\"id\":\"s1\",\"translation\":\"一\"},{\"id\":\"s1\",\"translation\":\"二\"}]", s, protected) }
    }
    @Test fun invalidOrUnfinishedOutputNeverValidates() {
        val s = listOf(SourceSentence("p1-b0-s0", "Equation $" + "x=2$.", "p1-b0"))
        val (_, protected) = StructuredTranslation.prompt(s, TranslationConfig())
        for (raw in listOf("{", "{\"translations\":[]}", "[{\"id\":\"s1\",\"translation\":\"\"}]", "<think>reason</think>[]",
            "[{\"id\":\"s1\",\"translation\":\"__PR0__ __PR0__\"}]")) {
            assertFails(raw) { StructuredTranslation.validate(raw, s, protected) }
        }
    }
    @Test fun draftsAreDecodedAndFragmentsRetainOriginalProtectedSpans() {
        val s = listOf(SourceSentence("p1-b0-s0", "Read 10 kg.", "g"))
        val (_, protected) = StructuredTranslation.prompt(s, TranslationConfig())
        val draft = StructuredTranslation.drafts("{\"translations\":[{\"id\":\"s1\",\"translation\":\"译文 __PR0__ \\\"quoted\\\"", s, protected)
        assertEquals("译文 10 kg \"quoted\"", draft.single().second)
        val formula = "$" + "x+y=42$"
        val original = "a".repeat(1198) + formula + " trailing text".repeat(150)
        val fragments = StructuredTranslation.fragments(original)
        assertTrue(fragments.size > 1); assertEquals(original, fragments.joinToString(""))
        assertEquals(1, fragments.count { formula in it })
    }
    @Test fun retriesAreFiniteAndInvalidDraftsNeverEnterCache() = runTest {
        var calls = 0
        val cache = object : TranslationCache { override suspend fun get(key: String): String? = null; override suspend fun put(key: String, text: String, translation: String, config: TranslationConfig) = fail("Invalid output reached cache") }
        val provider = object : TranslationProvider { override fun generate(prompt: ModelPrompt) = flow { calls++; emit(ProviderEvent.Delta("{\"translations\":[{\"id\":\"s1\",\"translation\":\"草稿")); emit(ProviderEvent.Complete("{}")) } }
        val done = CompletableDeferred<Unit>(); var drafts = 0
        TranslationQueue(backgroundScope, cache) { provider }.submit(TranslationWork("bad", 0, 0, listOf(SourceSentence("p1-b0-s0", "One.", "g")), TranslationConfig(),
            committed = { _,_,_ -> fail() }, finished = { failures, cancelled -> assertEquals(1, failures.size); assertFalse(cancelled); done.complete(Unit) }, delta = { _, _ -> drafts++ }))
        done.await(); assertEquals(3, calls); assertTrue(drafts > 0)
    }
    @Test fun cacheIdentityIncludesModelLanguageAndGlossary() {
        val c = TranslationConfig()
        assertEquals(contentKey("café   A", c), contentKey("cafe\u0301 A", c))
        assertNotEquals(contentKey("x", c), contentKey("x", c.copy(modelHash = "different")))
        assertNotEquals(contentKey("x", c), contentKey("x", c.copy(sourceLanguage = "en")))
        assertEquals(contentKey("x", c.copy(glossary = "{\"b\":\"B\",\"a\":\"A\"}")), contentKey("x", c.copy(glossary = "{\"a\":\"A\",\"b\":\"B\"}")))
    }
    @Test fun protocolRejectsBadVersionAndUtf8Limit() {
        val message = Envelope("reader.ready", "s", "d", "r", "q", 0, 0, JSONObject())
        assertEquals("reader.ready", Envelope.parse(message.json().toString()).type)
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
            assertTrue(server.takeRequest().body.readUtf8().contains("\"enable_thinking\":false"))
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
