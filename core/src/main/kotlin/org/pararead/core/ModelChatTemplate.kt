package org.pararead.core

/** Text-only, no-tools templates checked against the tested GGUFs' tokenizer.chat_template. */
object ModelChatTemplate {
    val architectures = setOf("qwen3", "qwen35", "lfm2")
    fun render(architecture: String, prompt: ModelPrompt): String = when (architecture) {
        "qwen3" -> Qwen3ChatTemplate.render(prompt)
        "qwen35" -> Qwen3ChatTemplate.render(prompt.copy(user = prompt.user.removeSuffix("\n/no_think")))
        // llama_tokenize(add_special=true) adds the LFM BOS from GGUF metadata exactly once.
        "lfm2" -> "<|im_start|>system\n${prompt.system}<|im_end|>\n<|im_start|>user\n${prompt.user.removeSuffix("\n/no_think")}<|im_end|>\n<|im_start|>assistant\n"
        else -> error("MODEL_TEMPLATE_UNSUPPORTED: $architecture; supported: qwen3 / qwen35 / lfm2")
    }
}
