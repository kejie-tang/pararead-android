package org.pararead.core

/** Qwen3 enable_thinking=false formatting: prefill an empty thinking block before any generated text. */
object Qwen3ChatTemplate {
    fun render(prompt: ModelPrompt): String =
        (if (prompt.system.isNotEmpty()) "<|im_start|>system\n${prompt.system}<|im_end|>\n" else "") +
            "<|im_start|>user\n${prompt.user}<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
}
