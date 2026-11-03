package org.pararead.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import kotlin.test.*

class TranslationQualityTest {
    private val english = "The proposed attention mechanism preserves contextual information across distant observations while reducing the computational cost of inference."
    private val chinese = "所提出的注意力机制保留了相距较远的观测中的上下文信息，同时降低了推理的计算成本。"

    /** Exercise the cache gate, structured rows and text-only output without semantic judgements. */
    private fun checkBoth(source: String, output: String, config: TranslationConfig = TranslationConfig()) {
        assertTrue(TranslationQuality.accepts(source, output, config), "helper: $output")
        val sentence = SourceSentence("quality", source, "g")
        val inputs = StructuredTranslation.prompt(listOf(sentence), config).second
        val textInput = StructuredTranslation.textPrompt(sentence, config).second
        assertEquals(source, inputs.single().text)
        assertEquals(source, textInput.text)
        val raw = JSONObject().put("translations", JSONArray().put(JSONObject().put("id", "s1").put("translation", output))).toString()
        val result = StructuredTranslation.validateEach(raw, listOf(sentence), inputs, config)
        assertEquals(listOf(sentence to output), result.accepted)
        assertTrue(result.rejected.isEmpty())
        assertEquals(output, StructuredTranslation.validateText(output, textInput, config))
        assertEquals(output, StructuredTranslation.validate(raw, listOf(sentence), inputs, config).single().second)
        assertEquals(output, inputs.single().forDisplay(output, source, config))
    }

    @Test fun copiedAndWrongLanguageOutputsAreNotQualityErrors() {
        for (target in listOf("zh-CN", "zh-TW", "zh-Hans", "zh-Hant", "zh", "zh_HK", "简体中文", "繁体中文", "en", "ja")) {
            val config = TranslationConfig(targetLanguage = target)
            checkBoth(english, english, config)
            checkBoth(english, "This architecture reduces inference overhead without losing contextual information.", config)
            checkBoth(english, "译 $english", config)
            checkBoth(english, chinese, config)
            checkBoth(chinese, english, config)
        }
        val passage = List(12) { english }.joinToString(" ")
        checkBoth(passage, passage)
    }

    @Test fun omittedOrChangedNumbersCitationsAndEquationsAreKeptWithoutWarnings() {
        val source = "Use 100 pages and 4 GB for ${'$'}E=mc^2${'$'} [12]."
        for (output in listOf("使用 99 页和 2 GB。", "只有译文。", source, "Unrelated output.")) {
            checkBoth(source, output)
        }
    }

    @Test fun codeEquationsNamesAndTargetLanguageTextRemainUnmodified() {
        for (source in listOf("${'$'}E=mc^2${'$'}", "E = mc^2", "https://example.org/paper/123", "4 GB [12]",
            "`cache.get(key)`", "Qwen3:1.7b", "GPT-4o", "llama.cpp", "gpt-oss", "arXiv", "BERT",
            "Albert Einstein", "John von Neumann", chinese, "該模型保留上下文資訊。")) {
            checkBoth(source, source)
        }
        checkBoth("Qwen3 and BERT use ${'$'}x+y${'$'} for the model proposed by Albert Einstein [12].",
            "Qwen3 和 BERT 使用 Albert Einstein 提出的模型中的 ${'$'}x+y${'$'} [12]。")
    }

    @Test fun aCopiedJsonRowDoesNotDiscardAValidNeighbour() {
        val sentences = listOf(SourceSentence("copy", english, "g"), SourceSentence("translated", english, "g"))
        val inputs = StructuredTranslation.prompt(sentences, TranslationConfig()).second
        val raw = JSONObject().put("translations", JSONArray()
            .put(JSONObject().put("id", "s1").put("translation", english))
            .put(JSONObject().put("id", "s2").put("translation", chinese))).toString()
        val result = StructuredTranslation.validateEach(raw, sentences, inputs)
        assertEquals(listOf(sentences[0] to english, sentences[1] to chinese), result.accepted)
        assertTrue(result.rejected.isEmpty())
    }

    @Test fun demoEchoesAreNonemptyOutputForEveryProvider() {
        val demo = "【模拟译文】$english"
        for (provider in listOf("mock", "api", "llama")) {
            checkBoth(english, demo, TranslationConfig(provider = provider))
            assertFalse(TranslationQuality.accepts(english, "", TranslationConfig(provider = provider)))
        }
    }

    @Test fun runtimeWarningSuffixIsDeduplicatedAndCannotMakeAnEmptyCacheUsable() {
        val errors = listOf("HTTP_500", "HOST_TIMEOUT", "http_500")
        val marked = TranslationWarnings.append(english, errors)
        assertEquals("$english（http_500, host_timeout）", marked)
        assertEquals(marked, TranslationWarnings.append(marked, errors))
        assertTrue(TranslationQuality.accepts(english, marked, TranslationConfig()))
        assertFalse(TranslationQuality.accepts(english, "（http_500）", TranslationConfig()))
        assertTrue(TranslationQuality.accepts(english, "$english（unknown_code）", TranslationConfig()))
        assertTrue(TranslationQuality.accepts(english, "$english（not a valid suffix）", TranslationConfig()))
        assertEquals("译文（http_500, truncated_stream_length）", TranslationWarnings.append("译文", listOf("HTTP_500", "TRUNCATED_STREAM_length", "http_500")))
        assertEquals("甲乙（http_500, host_timeout）",
            TranslationWarnings.join(listOf("甲（http_500）", "乙（host_timeout）")))
        val input = StructuredTranslation.textPrompt(SourceSentence("s", english, "g"), TranslationConfig()).second
        assertEquals("译文（http_500, host_timeout）", input.forDisplay("译文（http_500）", english, TranslationConfig(), errors))
    }

    @Test fun historicalQualityAnnotationsAreRemovedWhileRuntimeErrorsSurvive() {
        val config = TranslationConfig()
        val obsolete = listOf("protected_token_mismatch", "translation_language_or_source_copy")
        val input = StructuredTranslation.textPrompt(SourceSentence("s", english, "g"), config).second
        for (codes in listOf(obsolete.take(1), obsolete.drop(1), obsolete)) {
            val suffix = "（${codes.joinToString(", ")}）"
            for (value in listOf(english + suffix, "$english *****$suffix")) {
                assertTrue(TranslationQuality.accepts(english, value, config))
                assertEquals(english, TranslationWarnings.append(value, emptyList()))
                assertEquals(english, input.forDisplay(value, english, config))
                assertEquals("$english（host_timeout）", TranslationWarnings.append(value, codes + "HOST_TIMEOUT"))
            }
            assertFalse(TranslationQuality.accepts(english, suffix, config))
        }
        assertEquals("译文（http_500, host_timeout）", TranslationWarnings.append(
            "译文 *****（protected_token_mismatch, http_500）（translation_language_or_source_copy, host_timeout）", obsolete))
        assertEquals("甲乙（http_500）", TranslationWarnings.join(listOf(
            "甲（protected_token_mismatch）", "乙（translation_language_or_source_copy, http_500）")))
    }

    @Test fun cacheReuseCleansWarningsAndRejectsResidualPlaceholdersOnlyForLegacyKeys() {
        val candidates = translationCacheKeys(english, TranslationConfig())
        val current = candidates.single { !it.legacy }
        val legacy = candidates.single { it.legacy }
        for (candidate in candidates) {
            for (empty in listOf(null, "", "   ", "（http_500）", "*****（protected_token_mismatch）")) {
                assertNull(reuseCachedTranslation(empty, candidate))
            }
            assertEquals(english, reuseCachedTranslation(english, candidate))
            assertEquals("无关译文 99。", reuseCachedTranslation("无关译文 99。", candidate))
            assertEquals("$english（http_500）", reuseCachedTranslation(
                "$english *****（protected_token_mismatch, translation_language_or_source_copy, http_500）", candidate))
        }
        for (literal in listOf("__PR0__", "__PR0_", "__PR0", "原文 __PR12__。", "__PR001__（host_timeout）")) {
            assertNull(reuseCachedTranslation(literal, legacy))
            assertEquals(literal, reuseCachedTranslation(literal, current))
        }
    }

    @Test fun legacyStarRuntimeWarningsRemainReusableWithoutPlaceholderStars() {
        val config = TranslationConfig()
        for (value in listOf("译文 *****（http_500）", "译文（http_500）", "译文 *****（http_500）（host_timeout, http_500）")) {
            assertTrue(TranslationQuality.accepts(english, value, config))
            assertEquals("译文", TranslationWarnings.body(value))
            assertEquals("译文（http_500, host_timeout）", TranslationWarnings.append(value, listOf("HOST_TIMEOUT")))
        }
        assertEquals("译文（http_500）", TranslationWarnings.append("译文 *****（http_500）", emptyList()))
        assertEquals("甲乙（http_500, host_timeout）",
            TranslationWarnings.join(listOf("甲 *****（http_500）", "乙（host_timeout）")))
        for (empty in listOf("", "   ", "*****（http_500）", "（http_500）")) {
            assertFalse(TranslationQuality.accepts(english, empty, config))
        }
        assertEquals("正文 *** 与 *****（http_500） 不是尾缀（host_timeout）",
            TranslationWarnings.append("正文 *** 与 *****（http_500） 不是尾缀", listOf("HOST_TIMEOUT")))
    }
}
