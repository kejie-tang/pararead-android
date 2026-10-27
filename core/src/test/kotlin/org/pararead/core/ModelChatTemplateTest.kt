package org.pararead.core

import org.junit.Test
import kotlin.test.*

class ModelChatTemplateTest {
    @Test fun qwenKeepsNoThinkingPrefillWhileLfmUsesItsActualChatmlTemplateAndAutomaticBos() {
        val prompt = ModelPrompt("Translate.", "A sentence.\n/no_think")
        assertEquals(Qwen3ChatTemplate.render(prompt), ModelChatTemplate.render("qwen3", prompt))
        val qwen35 = ModelChatTemplate.render("qwen35", prompt)
        assertTrue(qwen35.endsWith("<think>\n\n</think>\n\n")); assertFalse(qwen35.contains("/no_think"))
        val lfm = ModelChatTemplate.render("lfm2", prompt)
        assertEquals("<|im_start|>system\nTranslate.<|im_end|>\n<|im_start|>user\nA sentence.<|im_end|>\n<|im_start|>assistant\n", lfm)
        assertFalse(lfm.contains("<think>")); assertFalse(lfm.contains("<|startoftext|>"))
        assertEquals(lfm, ModelChatTemplate.render("qwen2", prompt))
        assertFailsWith<IllegalStateException> { ModelChatTemplate.render("unsupported", prompt) }
    }
}
