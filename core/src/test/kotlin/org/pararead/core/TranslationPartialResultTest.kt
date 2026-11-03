package org.pararead.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import kotlin.test.*

class TranslationPartialResultTest {
    private val source = SourceSentence("a", "The reader opens a saved page.", "g")
    private val config = TranslationConfig()
    private val prefix = """{"translations":[{"id":"s1","translation":"已收到的部分译文"""
    private class Cache : TranslationCache {
        val values = mutableMapOf<String, String>()
        override suspend fun get(key: String) = values[key]
        override suspend fun put(key: String, text: String, translation: String, config: TranslationConfig) { values[key] = translation }
    }
    private fun salvage(raw: String, reason: String, sources: List<SourceSentence> = listOf(source)): TranslationValidation {
        val values = StructuredTranslation.prompt(sources, config).second
        return StructuredTranslation.salvage(raw, sources, values, config, reason)
    }

    @Test fun arbitraryStreamFailuresKeepTextWithTheirOwnCodeAndReopenWithoutInference() = runTest {
        for (reason in listOf("TRUNCATED_STREAM_length", "HTTP_500", "HOST_TIMEOUT", "OUTPUT_TOO_LARGE", "INVALID_NATIVE_TOKEN_METRICS")) {
            val cache = Cache(); var calls = 0
            val provider = object : TranslationProvider { override fun generate(prompt: ModelPrompt) = flow<ProviderEvent> {
                calls++; emit(ProviderEvent.Delta(prefix)); error(reason)
            } }
            val queue = TranslationQueue(backgroundScope, cache) { provider }
            repeat(2) { n ->
                val done = CompletableDeferred<Unit>()
                queue.submit(TranslationWork("$reason-$n", 0, 0, listOf(source), config,
                    committed = { _, value, key ->
                        assertEquals("已收到的部分译文（${reason.lowercase()}）", value)
                        assertEquals(value, cache.values[key])
                    }, finished = { failures, cancelled -> assertTrue(failures.isEmpty()); assertFalse(cancelled); done.complete(Unit) }))
                done.await()
            }
            assertEquals(1, calls); assertEquals(1, cache.values.size)
        }
    }

    @Test fun malformedCompleteEnvelopeRetainsAttributableRowsAndActualReason() {
        for ((raw, reason) in listOf(
            "$prefix\"}]" to "invalid_translation_json",
            "$prefix\"}]} extra" to "TRAILING_OUTPUT",
            prefix to "TRUNCATED_GENERATION")) {
            val recovered = salvage(raw, reason)
            assertEquals(listOf(source to "已收到的部分译文（${reason.lowercase()}）"), recovered.accepted)
            assertTrue(recovered.rejected.isEmpty())
        }
    }

    @Test fun onlyCompleteEscapesAreDecodedAndUnfinishedSurrogatesAreDropped() {
        for (tail in listOf("\\", "\\u4e", "\\uD83D")) {
            val value = salvage(prefix + tail, "TRUNCATED_GENERATION").accepted.single().second
            assertEquals("已收到的部分译文（truncated_generation）", value)
        }
        val quoted = """{"translations":[{"id":"s1","translation":"译文包含 \"引号\" 与 \\ 路径"""
        assertEquals("译文包含 \"引号\" 与 \\ 路径（truncated_generation）",
            salvage(quoted, "TRUNCATED_GENERATION").accepted.single().second)
    }

    @Test fun duplicateAndUnknownIdentitiesNeverBorrowAnotherSentencesText() {
        val sources = listOf(source, source.copy(id = "b"))
        val duplicate = """{"translations":[{"id":"s1","translation":"冲突一"},{"id":"s1","translation":"冲突二"},{"id":"s2","translation":"可归属的邻句"}]}"""
        val result = salvage(duplicate, "DUPLICATE_ID", sources)
        assertEquals(listOf(sources[1] to "可归属的邻句（duplicate_id）"), result.accepted)
        assertEquals(listOf(source to "DUPLICATE_ID"), result.rejected)
        val unknown = """{"translations":[{"id":"s99","translation":"不可归属"},{"id":"s1","translation":"本句译文"}]}"""
        val other = salvage(unknown, "UNKNOWN_ID", sources)
        assertEquals(listOf(source to "本句译文（unknown_id）"), other.accepted)
        assertEquals(listOf(sources[1] to "UNKNOWN_ID"), other.rejected)
        for (bad in listOf(
            """{"translations":[{"metadata":{"id":"s1","translation":"不是译文行"}}]}""",
            """{"translations":[{"id":"s1","translation":"身份冲突","id":"s2"}]}""",
            """{"translations":[{"id":"s1","translation":"未闭合的身份","id":"s2""",
            """{"translations":[{"id":"s1","translation":"有歧义的前缀\q","id":"s2""")) {
            assertTrue(salvage(bad, "INVALID_TRANSLATION_JSON", sources).accepted.isEmpty(), bad)
        }
    }

    @Test fun duplicatesWithoutBodiesAndBeyondTheOldRecoveryLimitStillBlockCaching() {
        val first = """{"translations":[{"id":"s1","translation":"甲"},"""
        for (raw in listOf(first + """{"id":"s1","translation":""",
            first + """{"id":"s1","translation":""".dropLast(1),
            first + """{"id":"s\u0031","translation":""",
            first + """{"\u0069d":"s1","translation":""",
            first + " ".repeat(70000) + """{"id":"s1","translation":"重复"}]}""")) {
            assertTrue(salvage(raw, "DUPLICATE_ID").accepted.isEmpty(), raw.take(200))
        }
    }

    @Test fun escapedPartialIdentityUsesTheSameJsonDecodingAsACompleteRow() {
        val raw = """{"translations":[{"id":"s\u0031","translation":"可归属的译文"""
        assertEquals(listOf(source to "可归属的译文（truncated_generation）"),
            salvage(raw, "TRUNCATED_GENERATION").accepted)
    }

    @Test fun unsafeDraftsAreNeverEmittedAndLaterIdentityConflictsClearEarlierText() = runTest {
        for (raw in listOf("""<think>{"id":"s1","translation":"秘密推理"}</think>""",
            """{"translations":[{"metadata":{"id":"s1","translation":"嵌套伪正文"}}]}""")) {
            val cache = Cache(); val drafts = mutableListOf<String>(); val done = CompletableDeferred<Unit>()
            val provider = object : TranslationProvider { override fun generate(prompt: ModelPrompt) = flow {
                emit(ProviderEvent.Delta(raw)); emit(ProviderEvent.Complete(raw))
            } }
            TranslationQueue(backgroundScope, cache) { provider }.submit(TranslationWork(raw.take(10), 0, 0, listOf(source), config,
                committed = { _, _, _ -> fail("Unsafe text") }, delta = { _, text -> drafts += text },
                finished = { failures, cancelled -> assertFalse(cancelled); assertEquals(1, failures.size); done.complete(Unit) }))
            done.await(); assertTrue(drafts.isEmpty()); assertTrue(cache.values.isEmpty())
        }
        val drafts = mutableListOf<String>(); val done = CompletableDeferred<Unit>(); val cache = Cache()
        val provider = object : TranslationProvider { override fun generate(prompt: ModelPrompt) = flow<ProviderEvent> {
            emit(ProviderEvent.Delta("""{"translations":[{"id":"s1","translation":"最初正文"},"""))
            emit(ProviderEvent.Delta("""{"id":"s1","translation":"""))
            error("TRUNCATED_STREAM_length")
        } }
        TranslationQueue(backgroundScope, cache) { provider }.submit(TranslationWork("conflict", 0, 0, listOf(source), config,
            committed = { _, _, _ -> fail("Ambiguous identity") }, delta = { _, text -> drafts += text },
            finished = { failures, _ -> assertEquals(1, failures.size); done.complete(Unit) }))
        done.await(); assertEquals(listOf("最初正文", ""), drafts); assertTrue(cache.values.isEmpty())
    }

    @Test fun firstLongFragmentSaveFailureStillPublishesItsCompleteBodyAsDraft() = runTest {
        val long = source.copy(text = List(60) { source.text }.joinToString(" "))
        val done = CompletableDeferred<Unit>(); val drafts = mutableListOf<String>(); var calls = 0; var writes = 0
        val cache = object : TranslationCache {
            override suspend fun get(key: String): String? = null
            override suspend fun put(key: String, text: String, translation: String, config: TranslationConfig) { writes++; error("CACHE_WRITE_FAILED") }
        }
        val provider = object : TranslationProvider { override fun generate(prompt: ModelPrompt) = flow {
            calls++; emit(ProviderEvent.Complete("$prefix\"}]}"))
        } }
        TranslationQueue(backgroundScope, cache) { provider }.submit(TranslationWork("fragment-save", 0, 0, listOf(long), config,
            committed = { _, _, _ -> fail("No durable acknowledgement") }, delta = { _, text -> drafts += text },
            finished = { failures, cancelled -> assertFalse(cancelled); assertEquals(listOf("a" to "CACHE_WRITE_FAILED"), failures); done.complete(Unit) }))
        done.await(); assertEquals(1, calls); assertEquals(2, writes)
        assertEquals(listOf("已收到的部分译文（cache_write_failed）"), drafts)
    }

    @Test fun reasoningAndJsonScaffoldingAreNeverUsedAsTranslationBodies() {
        val thought = """<think>{"translations":[{"id":"s1","translation":"秘密推理"}]}</think>"""
        val real = """{"translations":[{"id":"s1","translation":"真正译文"}]}"""
        assertEquals("真正译文（thinking_output）", salvage(thought + real, "THINKING_OUTPUT").accepted.single().second)
        for (bad in listOf(thought, "<think>推理尚未结束", "{", "{}", "[]", "   ",
            """{"translations":[{"id":"s1","translation":"（http_500）"}]}""")) {
            assertTrue(salvage(bad, "INVALID_TRANSLATION_JSON").accepted.isEmpty(), bad)
        }
        assertEquals("无 JSON 包装的译文（invalid_translation_json）",
            salvage("无 JSON 包装的译文", "INVALID_TRANSLATION_JSON").accepted.single().second)
        assertTrue(salvage("不可分配的多句文本", "INVALID_TRANSLATION_JSON", listOf(source, source.copy(id = "b"))).accepted.isEmpty())
    }

    @Test fun aMalformedFinalResultCanKeepTheLatestDraftButRestartCannotResurrectGpuText() = runTest {
        for (restart in listOf(false, true)) {
            val cache = Cache(); val done = CompletableDeferred<Unit>(); var calls = 0
            val provider = object : TranslationProvider { override fun generate(prompt: ModelPrompt) = flow {
                calls++; emit(ProviderEvent.Delta(prefix))
                if (restart) emit(ProviderEvent.Restart)
                emit(ProviderEvent.Complete("{}"))
            } }
            TranslationQueue(backgroundScope, cache) { provider }.submit(TranslationWork("restart-$restart", 0, 0, listOf(source), config,
                committed = { _, text, _ -> assertFalse(restart); assertEquals("已收到的部分译文（invalid_translation_json）", text) },
                finished = { failures, cancelled -> assertFalse(cancelled); assertEquals(restart, failures.isNotEmpty()); done.complete(Unit) }))
            done.await(); assertEquals(1, calls); assertEquals(if (restart) 0 else 1, cache.values.size)
        }
    }

    @Test fun saveFailureKeepsCompleteOutputAsDraftWithoutFalselyCommittingIt() = runTest {
        val done = CompletableDeferred<Unit>(); val drafts = mutableListOf<String>(); var calls = 0
        val cache = object : TranslationCache {
            override suspend fun get(key: String): String? = null
            override suspend fun put(key: String, text: String, translation: String, config: TranslationConfig) { error("CACHE_WRITE_FAILED") }
        }
        val provider = object : TranslationProvider { override fun generate(prompt: ModelPrompt) = flow {
            calls++; emit(ProviderEvent.Complete("$prefix\"}]}"))
        } }
        TranslationQueue(backgroundScope, cache) { provider }.submit(TranslationWork("save", 0, 0, listOf(source), config,
            committed = { _, _, _ -> fail("No durable acknowledgement") }, delta = { _, text -> drafts += text },
            finished = { failures, cancelled -> assertFalse(cancelled); assertEquals(listOf("a" to "CACHE_WRITE_FAILED"), failures); done.complete(Unit) }))
        done.await(); assertEquals(1, calls); assertEquals(listOf("已收到的部分译文"), drafts)
    }

    @Test fun apiTruncationKeepsReturnedTextInBothStreamingAndNonStreamingResponses() = runBlocking {
        for (streaming in listOf(false, true)) {
            val server = MockWebServer(); server.start()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val text = "$prefix\"}]}"
                if (streaming) {
                    val data = JSONObject().put("choices", JSONArray().put(JSONObject()
                        .put("delta", JSONObject().put("content", text)).put("finish_reason", "length")))
                    server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                        .setBody("data: $data\n\ndata: [DONE]\n\n"))
                } else {
                    val data = JSONObject().put("choices", JSONArray().put(JSONObject()
                        .put("message", JSONObject().put("content", text)).put("finish_reason", "length")))
                    server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(data.toString()))
                }
                val cache = Cache(); val done = CompletableDeferred<Unit>()
                val provider = OpenAICompatibleProvider(server.url("/v1").toString(), "test")
                TranslationQueue(scope, cache) { provider }.submit(TranslationWork("http", 0, 0, listOf(source), config,
                    committed = { _, value, _ ->
                        assertEquals("已收到的部分译文（${if (streaming) "truncated_stream_length" else "truncated_result"}）", value)
                    }, finished = { failures, cancelled -> assertFalse(cancelled); assertTrue(failures.isEmpty()); done.complete(Unit) }))
                withTimeout(5000) { done.await() }
                assertEquals(1, server.requestCount); assertEquals(1, cache.values.size)
            } finally { scope.cancel(); server.shutdown() }
        }
    }
}
