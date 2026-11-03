package org.pararead.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import kotlin.test.*

class TranslationQualityTest {
    private val english = "The proposed attention mechanism preserves contextual information across distant observations while reducing the computational cost of inference."
    private val chinese = "所提出的注意力机制保留了相距较远的观测中的上下文信息，同时降低了推理的计算成本。"

    /** Exercise both output paths, not just the public cache-reader helper. */
    private fun checkBoth(source: String, output: String, config: TranslationConfig, accepted: Boolean) {
        assertEquals(accepted, TranslationQuality.accepts(source, output, config), "helper: $output")
        val sentence = SourceSentence("quality", source, "g")
        val protected = StructuredTranslation.protect(source)
        var encoded = output
        protected.tokens.forEachIndexed { i, token -> encoded = encoded.replace(token, "__PR${i}__") }
        val raw = JSONObject().put("translations", JSONArray().put(JSONObject().put("id", "s1").put("translation", encoded))).toString()
        val result = StructuredTranslation.validateEach(raw, listOf(sentence), listOf(protected), config)
        val displayed = if (accepted) output else "$output *****（translation_language_or_source_copy）"
        assertEquals(listOf(sentence to displayed), result.accepted)
        assertTrue(result.rejected.isEmpty())
        assertEquals(displayed, StructuredTranslation.validateText(encoded, protected, config))
        assertEquals(displayed, StructuredTranslation.validate(raw, listOf(sentence), listOf(protected), config).single().second)
        assertTrue(TranslationQuality.accepts(source, displayed, config), "Marked output must remain reusable")
    }

    @Test fun englishSentenceAndLongPassageCannotBeChineseTranslations() {
        for (source in listOf(english, List(12) { english }.joinToString(" "))) {
            checkBoth(source, source, TranslationConfig(), false)
        }
        checkBoth(english, "This architecture reduces inference overhead without losing contextual information.", TranslationConfig(), false)
        checkBoth(english, chinese, TranslationConfig(), true)
    }

    @Test fun sprinklingHanIntoAnAlmostVerbatimSentenceDoesNotMakeATranslation() {
        for (bad in listOf("译 $english", "$english 中译", english.replace("attention", "注意 attention"),
            "译 " + english.uppercase().replace("observations", "OBSERVATIONS"))) {
            checkBoth(english, bad, TranslationConfig(), false)
        }
    }

    @Test fun simplifiedAndTraditionalTargetsUseTheSameQualityGate() {
        for (target in listOf("zh-CN", "zh-TW", "zh-Hans", "zh-Hant", "zh", "zh_HK", "简体中文", "繁体中文")) {
            val config = TranslationConfig(targetLanguage = target)
            checkBoth(english, english, config, false)
            checkBoth(english, "該注意力機制保留上下文資訊，同時降低推論的計算成本。", config, true)
        }
    }

    @Test fun protectedAndLanguageNeutralContentDoesNotNeedHan() {
        for (source in listOf("${'$'}E=mc^2${'$'}", "E = mc^2", "https://example.org/paper/123", "4 GB [12]",
            "`cache.get(key)`", "```python\nprint(42)\n```", "Qwen3:1.7b", "GPT-4o", "llama.cpp", "gpt-oss", "arXiv", "BERT", "Albert Einstein", "John von Neumann")) {
            checkBoth(source, source, TranslationConfig(), true)
        }
    }

    @Test fun chineseProseCanRetainTermsEquationsReferencesAndNames() {
        checkBoth("Qwen3 and BERT use ${'$'}x+y${'$'} for the model proposed by Albert Einstein [12].",
            "Qwen3 和 BERT 使用 Albert Einstein 提出的模型中的 ${'$'}x+y${'$'} [12]。", TranslationConfig(), true)
        checkBoth("The optimizer supports Adam SGD RMSProp AdaGrad AdamW Adadelta and Lion in every experiment.",
            "该优化器在每个实验中支持 Adam SGD RMSProp AdaGrad AdamW Adadelta 和 Lion。", TranslationConfig(), true)
    }

    @Test fun contentAlreadyInTheTargetLanguageMayStayUnchanged() {
        for (source in listOf(chinese, "該模型保留上下文資訊。", "该模型使用 Qwen3:1.7b 进行推理。",
            "优化器 Adam SGD RMSProp AdaGrad AdamW Adadelta Lion Momentum Nesterov")) {
            checkBoth(source, source, TranslationConfig(), true)
        }
        checkBoth(english, english, TranslationConfig(targetLanguage = "en"), true)
        checkBoth(english, "これは翻訳です。", TranslationConfig(targetLanguage = "ja"), true)
    }

    @Test fun protectedEnglishIsNotEvidenceOfUntranslatedProseOrOfChinese() {
        checkBoth("Evaluate `the model preserves all contextual information across distant observations` using ${'$'}x=2${'$'}.",
            "使用 ${'$'}x=2${'$'} 评估 `the model preserves all contextual information across distant observations`。", TranslationConfig(), true)
        checkBoth(english, "${'$'}中文${'$'} " + english, TranslationConfig(), false)
    }

    @Test fun aCopiedJsonRowDoesNotDiscardAValidNeighbour() {
        val sentences = listOf(SourceSentence("bad", english, "g"), SourceSentence("good", english, "g"))
        val protected = StructuredTranslation.prompt(sentences, TranslationConfig()).second
        val raw = JSONObject().put("translations", JSONArray()
            .put(JSONObject().put("id", "s1").put("translation", english))
            .put(JSONObject().put("id", "s2").put("translation", chinese))).toString()
        val result = StructuredTranslation.validateEach(raw, sentences, protected)
        assertEquals(listOf(sentences[0] to "$english *****（translation_language_or_source_copy）", sentences[1] to chinese), result.accepted)
        assertTrue(result.rejected.isEmpty())
    }

    @Test fun explicitlyLabelledDemoEchoesRemainSeparateFromRealModelValidation() {
        val demo = "【模拟译文】$english"
        assertTrue(TranslationQuality.accepts(english, demo, TranslationConfig(provider = "mock")))
        assertFalse(TranslationQuality.accepts(english, demo, TranslationConfig(provider = "api")))
        assertFalse(TranslationQuality.accepts(english, "", TranslationConfig(provider = "mock")))
    }

    @Test fun warningSuffixIsDeduplicatedAndCannotMakeAnEmptyCacheUsable() {
        val both = listOf(TranslationWarnings.PROTECTED, TranslationWarnings.LANGUAGE)
        val marked = TranslationWarnings.append(english, both)
        assertEquals("$english *****（protected_token_mismatch, translation_language_or_source_copy）", marked)
        assertEquals(marked, TranslationWarnings.append(marked, both))
        assertTrue(TranslationQuality.accepts(english, marked, TranslationConfig()))
        assertFalse(TranslationQuality.accepts(english, " *****（translation_language_or_source_copy）", TranslationConfig()))
        assertTrue(TranslationQuality.accepts(english, "$english *****（unknown_code）", TranslationConfig()))
        assertEquals("译文 *****（http_500, truncated_stream_length）", TranslationWarnings.append("译文", listOf("HTTP_500", "TRUNCATED_STREAM_length", "http_500")))
        assertFalse(TranslationQuality.accepts(english, "$english *****（not a valid suffix）", TranslationConfig()))
        assertEquals("甲乙 *****（protected_token_mismatch, translation_language_or_source_copy）",
            TranslationWarnings.join(listOf("甲 *****（protected_token_mismatch）", "乙 *****（translation_language_or_source_copy）")))
    }

    @Test fun blankCacheValuesAndEnglishOutputsForChineseSourcesAreRejected() {
        assertFalse(TranslationQuality.accepts(english, "   ", TranslationConfig()))
        checkBoth(chinese, english, TranslationConfig(), false)
    }
}
