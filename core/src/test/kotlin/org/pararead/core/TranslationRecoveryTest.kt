package org.pararead.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.Test
import kotlin.test.*

class TranslationRecoveryTest {
    private val sentences = listOf(
        SourceSentence("a", "The model processes 100 pages using 4 GB of memory [12].", "p1"),
        SourceSentence("b", "For ${'$'}E=mc^2${'$'}, retain the equation.", "p1"))
    private val partial = """{"translations":[{"id":"s1","translation":"模型使用了 __PR0__ 页，其中 __PR1__ 页。"},{"id":"s2","translation":"保留 __PR3__ 方程。"}]}"""
    private val warning = " *****（protected_token_mismatch）"
    private val imperfect = "模型使用了 100 页，其中 4 GB 页。$warning"
    private val good = "保留 ${'$'}E=mc^2${'$'} 方程。"

    private class Cache : TranslationCache {
        val values = mutableMapOf<String, String>()
        val writes = mutableListOf<String>()
        override suspend fun get(key: String) = values[key]
        override suspend fun put(key: String, text: String, translation: String, config: TranslationConfig) {
            values[key] = translation; writes += key
        }
    }

    @Test fun textPromptDoesNotRequestJsonFromApiServer() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"finish_reason":"stop","message":{"content":"完整译文"}}]}"""))
            val provider = OpenAICompatibleProvider(server.url("/v1").toString(), "test")
            val events = provider.generate(ModelPrompt("system", "source", outputFormat = ModelOutputFormat.TEXT)).toList()
            assertEquals("完整译文", (events.last() as ProviderEvent.Complete).text)
            assertFalse(JSONObject(server.takeRequest().body.readUtf8()).has("response_format"))
        } finally { server.shutdown() }
    }

    @Test fun retryableHttpResponsesDoNotReplayTranslationPosts() = runBlocking {
        for (code in listOf(408, 503)) {
            val server = MockWebServer(); server.start()
            try {
                server.enqueue(MockResponse().setResponseCode(code).setHeader("Retry-After", "0").setBody("temporary failure"))
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("""{"choices":[{"finish_reason":"stop","message":{"content":"不应被第二次调用"}}]}"""))
                val provider = OpenAICompatibleProvider(server.url("/v1").toString(), "test")
                val failure = assertFailsWith<IllegalStateException> { provider.generate(ModelPrompt("system", "source")).toList() }
                assertEquals("HTTP_$code", failure.message)
                assertEquals(1, server.requestCount)
            } finally { server.shutdown() }
        }
    }

    @Test fun disconnectedTranslationPostIsNotResubmitted() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST))
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"choices":[{"finish_reason":"stop","message":{"content":"不应被第二次调用"}}]}"""))
            val provider = OpenAICompatibleProvider(server.url("/v1").toString(), "test")
            assertFails { provider.generate(ModelPrompt("system", "source")).toList() }
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test fun missingCitationsBecomePlainWarningsWithoutDiscardingEitherRow() {
        val protected = StructuredTranslation.prompt(sentences, TranslationConfig()).second
        val result = StructuredTranslation.validateEach(partial, sentences, protected)
        assertEquals(listOf(sentences[0] to imperfect, sentences[1] to good), result.accepted)
        assertTrue(result.rejected.isEmpty())
        assertEquals(result.accepted, StructuredTranslation.validate(partial, sentences, protected))
        val missing = """{"translations":[{"id":"s2","translation":"保留 __PR3__ 方程。"}]}"""
        val other = StructuredTranslation.validateEach(missing, sentences, protected)
        assertEquals(listOf(sentences[1] to good), other.accepted)
        assertEquals(listOf(sentences[0] to "RESULT_COUNT_MISMATCH"), other.rejected)
    }

    @Test fun ambiguousIncompleteAndUnsafeEnvelopesStillFail() {
        val protected = StructuredTranslation.prompt(sentences, TranslationConfig()).second
        for (bad in listOf(partial.dropLast(1), partial + " extra", partial.replace("\"s1\"", "\"s2\""),
            partial.replace("\"s1\"", "\"s3\""), "<think>reason</think>$partial")) {
            assertFails(bad) { StructuredTranslation.validateEach(bad, sentences, protected) }
        }
    }

    @Test fun bestEffortRestoresOnlyOwnedMarkersAndNeverInventsMissingContent() {
        val config = TranslationConfig(sourceLanguage = "en", glossary = """{"model":"模型","zh-CN":{"memory":"内存"}}""")
        val (prompt, protected) = StructuredTranslation.textPrompt(sentences[0], config)
        assertTrue(prompt.system.contains("内存")); assertTrue(prompt.system.contains("模型"))
        assertEquals("模型使用 4 GB 内存处理 100 页 [12]。",
            StructuredTranslation.validateText("模型使用 __PR1__ 内存处理 __PR0__ 页 __PR2__。", protected, config))
        for ((raw, expected) in listOf(
            "译文 __PR0__ __PR1__" to "译文 100 4 GB",
            "译文 __PR0__ __PR0__ __PR2__" to "译文 100 100 [12]",
            "译文 __PR00__ __PR1__ __PR2__" to "译文 __PR00__ 4 GB [12]",
            "译文 __PR0_ __PR1__ __PR2__" to "译文 __PR0_ 4 GB [12]",
            "译文完全没有标记" to "译文完全没有标记")) {
            assertEquals(expected + warning, StructuredTranslation.validateText(raw, protected, config))
        }
        val foreign = StructuredTranslation.prompt(sentences, config).second[1]
        assertEquals("保留 __PR0__ 方程。$warning", foreign.forDisplay("保留 __PR0__ 方程。", sentences[1].text, config))
        for (bad in listOf("", "   ", "<think>推理</think>译文", partial, "```译文```")) {
            assertFails(bad) { StructuredTranslation.validateText(bad, protected, config) }
        }
    }

    @Test fun imperfectBatchIsGeneratedOnceAndReopenedFromCache() = runTest {
        val prompts = mutableListOf<ModelPrompt>(); val cache = Cache(); val commits = mutableListOf<Pair<String, String>>()
        val provider = object : TranslationProvider { override fun generate(prompt: ModelPrompt) = flow {
            prompts += prompt; emit(ProviderEvent.Complete(partial))
        } }
        val config = TranslationConfig(sourceLanguage = "en")
        val fingerprint = config.fingerprint()
        val queue = TranslationQueue(backgroundScope, cache) { provider }
        suspend fun open(id: String) {
            val done = CompletableDeferred<Unit>()
            assertTrue(queue.submit(TranslationWork(id, 0, 0, sentences, config,
                committed = { source, value, key -> assertEquals(value, cache.values[key]); commits += source.id to value },
                finished = { failures, cancelled -> assertTrue(failures.isEmpty()); assertFalse(cancelled); done.complete(Unit) })))
            done.await()
        }
        open("first")
        assertEquals(listOf("a" to imperfect, "b" to good), commits)
        assertEquals(1, prompts.size)
        assertEquals(ModelOutputFormat.TRANSLATION_JSON, prompts.single().outputFormat)
        open("reopen")
        assertEquals(1, prompts.size); assertEquals(2, cache.writes.size)
        assertEquals(listOf("a" to imperfect, "b" to good).repeatTwice(), commits)
        assertEquals(fingerprint, config.fingerprint())
    }

    private fun <T> List<T>.repeatTwice() = this + this

    @Test fun missingEmptyAndWrongTypeRowsFailOnceWhileGoodNeighbourIsSaved() = runTest {
        for (value in listOf("null", "17", "\"\"", "\"   \"")) {
            var calls = 0; val cache = Cache(); val committed = mutableListOf<String>()
            val provider = object : TranslationProvider { override fun generate(prompt: ModelPrompt) = flow {
                calls++; emit(ProviderEvent.Complete("""{"translations":[{"id":"s1","translation":$value},{"id":"s2","translation":"保留 __PR3__ 方程。"}]}"""))
            } }
            val done = CompletableDeferred<Unit>()
            TranslationQueue(backgroundScope, cache) { provider }.submit(TranslationWork("failed-$value", 0, 0, sentences, TranslationConfig(),
                committed = { source, _, _ -> committed += source.id },
                finished = { failures, cancelled ->
                    assertFalse(cancelled); assertEquals(listOf("a"), failures.map { it.first }); done.complete(Unit)
                }))
            done.await(); assertEquals(1, calls); assertEquals(listOf("b"), committed); assertEquals(1, cache.writes.size)
        }
    }

    @Test fun transportAndMalformedOutputAreNotRetried() = runTest {
        for (error in listOf("HTTP_429", "TRUNCATED_STREAM_length", "INFERENCE_FAILED", "HOST_TIMEOUT", "OUTPUT_TOO_LARGE", "malformed")) {
            var calls = 0; val cache = Cache(); val done = CompletableDeferred<Unit>()
            val provider = object : TranslationProvider { override fun generate(prompt: ModelPrompt) = flow<ProviderEvent> {
                calls++
                if (error == "malformed") emit(ProviderEvent.Complete("{")) else error(error)
            } }
            TranslationQueue(backgroundScope, cache) { provider }.submit(TranslationWork(error, 0, 0, sentences, TranslationConfig(),
                committed = { _, _, _ -> fail("No complete translation") },
                finished = { failures, cancelled -> assertFalse(cancelled); assertEquals(2, failures.size); done.complete(Unit) }))
            done.await(); assertEquals(1, calls); assertTrue(cache.writes.isEmpty())
        }
    }

    @Test fun cancellingFirstGenerationDrainsWithoutCommittingItsDraft() = runTest {
        val begun = CompletableDeferred<Unit>(); val stopped = CompletableDeferred<Unit>(); val done = CompletableDeferred<Unit>()
        val cache = Cache(); var calls = 0
        val provider = object : TranslationProvider { override fun generate(prompt: ModelPrompt) = flow {
            calls++; emit(ProviderEvent.Delta("{\"translations\":[{\"id\":\"s1\",\"translation\":\"未完成译文")); begun.complete(Unit)
            try { awaitCancellation() } finally { stopped.complete(Unit) }
        } }
        val queue = TranslationQueue(backgroundScope, cache) { provider }
        queue.submit(TranslationWork("cancel", 0, 0, sentences, TranslationConfig(), committed = { _, _, _ -> fail() },
            finished = { _, cancelled -> assertTrue(cancelled); done.complete(Unit) }))
        begun.await(); queue.cancel("cancel"); done.await(); stopped.await()
        assertEquals(1, calls); assertTrue(cache.writes.isEmpty())
    }
}
