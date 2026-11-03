package org.pararead.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import kotlin.test.*

class ThinkingOutputTest {
    private val source = SourceSentence("source", "Document the `<think>x</think>` tag.", "g")
    private val config = TranslationConfig()
    private val inputs = StructuredTranslation.prompt(listOf(source), config).second
    private fun json(text: String) = JSONObject().put("translations", JSONArray()
        .put(JSONObject().put("id", "s1").put("translation", text))).toString()
    private fun salvage(raw: String, reason: String = "THINKING_OUTPUT") =
        StructuredTranslation.salvage(raw, listOf(source), inputs, config, reason)

    @Test fun jsonStringLiteralsAndEscapedQuotesAreNeverRewrittenAsReasoning() {
        for (text in listOf("标签 `<think>x</think>`。", "文本 \"<think>\" 与 \\ 路径。", "字面 </think> 结束标签。")) {
            assertEquals(text, StructuredTranslation.validate(json(text), listOf(source), inputs).single().second)
            assertEquals(text + "（http_500）", salvage(json(text), "HTTP_500").accepted.single().second)
            assertEquals(text, StructuredTranslation.drafts(json(text), listOf(source), inputs).single().second)
        }
    }

    @Test fun partialJsonStringKeepsLiteralTagsInBothDraftAndRecovery() {
        val prefix = "{\"translations\":[{\"id\":\"s1\",\"translation\":\"标签 `<think>x</think>`"
        assertEquals("标签 `<think>x</think>`", StructuredTranslation.drafts(prefix, listOf(source), inputs).single().second)
        assertEquals("标签 `<think>x</think>`（truncated_stream_length）",
            salvage(prefix, "TRUNCATED_STREAM_length").accepted.single().second)
    }

    @Test fun externalThinkingIgnoresItsQuotesJsonAndNestedBlocks() {
        val output = "真正译文 `<think>x</think>`。"
        val thought = "<think>\"unclosed quote {\"translations\":[{\"id\":\"s1\",\"translation\":\"秘密\"}]}" +
            "<think>嵌套秘密</think>更多推理</think>"
        assertFails { StructuredTranslation.validate(thought + json(output), listOf(source), inputs) }
        assertEquals(output + "（thinking_output）", salvage(thought + json(output)).accepted.single().second)
        assertTrue(salvage(thought).accepted.isEmpty())
        assertTrue(StructuredTranslation.drafts(thought, listOf(source), inputs).isEmpty())
    }

    @Test fun unclosedOrOrphanedReasoningCannotLeakIntoRecoveredText() {
        for (raw in listOf("<think>秘密推理", "<think><think>秘密</think>仍未结束",
            "秘密</think>" + json("不应取前面的秘密"), "\"<think>秘密</think>\"")) {
            assertTrue(salvage(raw).accepted.isEmpty(), raw)
        }
        assertEquals("有效译文（thinking_output）", salvage(json("有效译文") + "<think>未闭合推理").accepted.single().second)
    }

    @Test fun removedReasoningLeavesAWhitespaceTokenBoundary() {
        val result = ThinkingOutput.structured("1<think>秘密</think>2")
        assertTrue(result.hadThinking)
        assertEquals("1 ", result.text, "ambiguous unquoted values stop recovery instead of being reassembled")
        val malformed = "{\"translations\":[{\"id\":\"s1\",\"translation\":1<think>秘密</think>2}]}"
        assertTrue(salvage(malformed).accepted.isEmpty())
    }

    @Test fun textModeAllowsOnlyCompleteSourceOwnedLiteralSnippets() {
        val textSource = SourceSentence("text", "Document `<think>` and \"</think>\".", "g")
        val input = StructuredTranslation.textPrompt(textSource, config).second
        val output = "说明 `<think>` 和 \"</think>\"。"
        assertEquals(output, StructuredTranslation.validateText(output, input, config))
        for (bad in listOf("<think>真实推理</think>译文", "\"<think>真实推理</think>\"译文",
            "`<think>` `<think>`", "`<think>", "`<think>不同正文</think>`")) {
            assertFails(bad) { StructuredTranslation.validateText(bad, input, config) }
        }
        val fenced = "```html\n<think>x</think>\n```"
        val fencedInput = StructuredTranslation.textPrompt(source.copy(text = "Code: $fenced"), config).second
        assertEquals(fenced, StructuredTranslation.validateText("Code: $fenced", fencedInput, config).removePrefix("Code: "))
    }

    @Test fun plainFallbackKeepsOwnedLiteralTagsButDoesNotAdmitQuotedReasoning() {
        val text = "说明 `<think>x</think>`。"
        assertEquals(text + "（invalid_translation_json）", salvage(text, "INVALID_TRANSLATION_JSON").accepted.single().second)
        assertTrue(salvage("\"<think>新的秘密</think>\"", "INVALID_TRANSLATION_JSON").accepted.isEmpty())
    }
}
