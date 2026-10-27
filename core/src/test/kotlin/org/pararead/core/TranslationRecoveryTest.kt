package org.pararead.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Test
import kotlin.test.*

class TranslationRecoveryTest {
    private val sentences = listOf(
        SourceSentence("a", "The model processes 100 pages using 4 GB of memory [12].", "p1"),
        SourceSentence("b", "For ${'$'}E=mc^2${'$'}, retain the equation.", "p1"))
    // The failed first row from the actual Android Qwen3-0.6B Q4_0 run.
    private val partial = """{"translations":[{"id":"s1","translation":"模型使用了 __PR0__ 页，其中 __PR1__ 页。"},{"id":"s2","translation":"保留 __PR3__ 方程。"}]}"""

    @Test fun textRecoveryDoesNotRequestJsonFromApiServer() = runBlocking {
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

    @Test fun independentlyValidRowsSurviveMissingCitationsAndMissingRows() {
        val protected = StructuredTranslation.prompt(sentences, TranslationConfig()).second
        val result = StructuredTranslation.validateEach(partial, sentences, protected)
        assertEquals(listOf(sentences[1] to "保留 ${'$'}E=mc^2${'$'} 方程。"), result.accepted)
        assertEquals(listOf(sentences[0] to "PROTECTED_TOKEN_MISMATCH"), result.rejected)
        assertFails { StructuredTranslation.validate(partial, sentences, protected) }
        val missing = """{"translations":[{"id":"s2","translation":"保留 __PR3__ 方程。"}]}"""
        assertEquals(result.accepted, StructuredTranslation.validateEach(missing, sentences, protected).accepted)
        assertEquals("RESULT_COUNT_MISMATCH", StructuredTranslation.validateEach(missing, sentences, protected).rejected.single().second)
    }

    @Test fun ambiguousOrIncompleteEnvelopeCannotSalvageRows() {
        val protected = StructuredTranslation.prompt(sentences, TranslationConfig()).second
        for (bad in listOf(partial.dropLast(1), partial + " extra", partial.replace("\"s1\"", "\"s2\""), partial.replace("\"s1\"", "\"s3\""))) {
            assertFails(bad) { StructuredTranslation.validateEach(bad, sentences, protected) }
        }
    }

    @Test fun textRecoveryRetainsGlossaryAndRejectsMissingDuplicateOrRenumberedMarkers() {
        val config = TranslationConfig(sourceLanguage = "en", glossary = """{"model":"模型","zh-CN":{"memory":"内存"}}""")
        val (prompt, protected) = StructuredTranslation.textPrompt(sentences[0], config)
        assertEquals(ModelOutputFormat.TEXT, prompt.outputFormat)
        assertTrue(prompt.system.contains("内存")); assertTrue(prompt.system.contains("模型"))
        assertTrue(prompt.system.contains("__PR数字__")); assertTrue(prompt.user.contains("__PR2__"))
        assertEquals("模型使用 4 GB 内存处理 100 页 [12]。", StructuredTranslation.validateText("模型使用 __PR1__ 内存处理 __PR0__ 页 __PR2__。", protected, config))
        for (bad in listOf("译文 __PR0__ __PR1__", "译文 __PR0__ __PR0__ __PR2__", "译文 __PR00__ __PR1__ __PR2__",
            "English __PR0__ __PR1__ __PR2__", "<think>推理</think>译文 __PR0__ __PR1__ __PR2__", partial,
            """{"id":"s1","translation":"模型 __PR0__ __PR1__ __PR2__"}""",
            """{"translation":"模型 __PR0__ __PR1__ __PR2__""")) {
            assertFails(bad) { StructuredTranslation.validateText(bad, protected, config) }
        }
        val (other, _) = StructuredTranslation.textPrompt(sentences[0], config.copy(targetLanguage = "ja"))
        assertTrue(other.system.contains("into ja")); assertFalse(other.system.contains("Simplified Chinese"))
    }

    @Test fun onlyFailedSentenceIsRetriedAndTextFallbackCanRecoverIt() = runTest {
        val prompts = mutableListOf<ModelPrompt>(); val values = mutableMapOf<String, String>(); val commits = mutableListOf<String>()
        val cache = object : TranslationCache {
            override suspend fun get(key: String) = values[key]
            override suspend fun put(key: String, text: String, translation: String, config: TranslationConfig) { values[key] = translation }
        }
        val provider = object : TranslationProvider {
            override fun generate(prompt: ModelPrompt) = flow {
                prompts += prompt
                emit(ProviderEvent.Complete(when (prompts.size) {
                    1 -> partial
                    2 -> """{"translations":[{"id":"s1","translation":"仍缺引用 __PR0__ __PR1__"}]}"""
                    else -> "模型使用 __PR1__ 内存处理 __PR0__ 页 __PR2__。"
                }))
            }
        }
        val queue = TranslationQueue(backgroundScope, cache) { provider }
        suspend fun open(id: String) {
            val done = CompletableDeferred<Unit>()
            assertTrue(queue.submit(TranslationWork(id, 0, 0, sentences, TranslationConfig(sourceLanguage = "en"),
                committed = { s, _, key -> assertTrue(key in values); commits += s.id },
                finished = { failures, cancelled -> assertTrue(failures.isEmpty()); assertFalse(cancelled); done.complete(Unit) })))
            done.await()
        }
        open("first")
        assertEquals(listOf("b", "a"), commits)
        assertEquals(3, prompts.size)
        assertEquals(listOf(ModelOutputFormat.TRANSLATION_JSON, ModelOutputFormat.TRANSLATION_JSON, ModelOutputFormat.TEXT), prompts.map { it.outputFormat })
        assertTrue(prompts[1].system.contains("Required markers"))
        for (prompt in prompts.drop(1)) assertFalse(prompt.user.contains("retain the equation"))
        open("reopen"); assertEquals(3, prompts.size); assertEquals(2, values.size)
    }

    @Test fun exhaustedRecoveryOnlyFailsItsOwnSentenceAndDoesNotSaveInvalidText() = runTest {
        var calls = 0; val saved = mutableListOf<String>(); val committed = mutableListOf<String>()
        val cache = object : TranslationCache {
            override suspend fun get(key: String): String? = null
            override suspend fun put(key: String, text: String, translation: String, config: TranslationConfig) { saved += text }
        }
        val provider = object : TranslationProvider { override fun generate(prompt: ModelPrompt) = flow {
            calls++
            emit(ProviderEvent.Complete(if (calls == 1) partial else if (prompt.outputFormat == ModelOutputFormat.TEXT) "译文 __PR0__ __PR1__" else "{}"))
        } }
        val done = CompletableDeferred<Unit>()
        TranslationQueue(backgroundScope, cache) { provider }.submit(TranslationWork("failed", 0, 0, sentences, TranslationConfig(),
            committed = { s, _, _ -> committed += s.id },
            finished = { failures, cancelled ->
                assertFalse(cancelled); assertEquals(listOf("a"), failures.map { it.first }); done.complete(Unit)
            }))
        done.await(); assertEquals(3, calls); assertEquals(listOf("b"), committed); assertEquals(listOf(sentences[1].text), saved)
    }

    @Test fun cancellingPlainRecoverySettlesWithoutCommittingItsDraft() = runTest {
        val begun = CompletableDeferred<Unit>(); val stopped = CompletableDeferred<Unit>(); val done = CompletableDeferred<Unit>()
        val saved = mutableListOf<String>(); var calls = 0
        val cache = object : TranslationCache {
            override suspend fun get(key: String): String? = null
            override suspend fun put(key: String, text: String, translation: String, config: TranslationConfig) { saved += text }
        }
        val provider = object : TranslationProvider { override fun generate(prompt: ModelPrompt) = flow {
            calls++
            if (prompt.outputFormat == ModelOutputFormat.TEXT) {
                emit(ProviderEvent.Delta("未校验 __PR0__")); begun.complete(Unit)
                try { awaitCancellation() } finally { stopped.complete(Unit) }
            } else emit(ProviderEvent.Complete(if (calls == 1) partial else "{}"))
        } }
        val queue = TranslationQueue(backgroundScope, cache) { provider }
        queue.submit(TranslationWork("cancel", 0, 0, sentences, TranslationConfig(), committed = { _, _, _ -> },
            finished = { _, cancelled -> assertTrue(cancelled); done.complete(Unit) },
            delta = { _, _ -> fail("Unvalidated plaintext must not be shown") }))
        begun.await(); queue.cancel("cancel"); done.await(); stopped.await()
        assertEquals(listOf(sentences[1].text), saved)
    }
}
