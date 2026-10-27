package org.pararead.llama

enum class InferenceBackend(val label: String) {
    CPU("CPU"), VULKAN("Vulkan GPU（实验）");
    companion object { fun from(value: String?) = entries.firstOrNull { it.name == value } ?: CPU }
}

data class BackendStatus(
    val requested: InferenceBackend,
    val actual: InferenceBackend? = null,
    val device: String = "",
    val fallbackReason: String = "",
) {
    val description: String get() = when {
        fallbackReason.isNotEmpty() -> "已回退 CPU：$fallbackReason"
        actual == InferenceBackend.VULKAN -> "实际后端：Vulkan GPU · $device"
        actual == InferenceBackend.CPU -> "实际后端：CPU"
        else -> "请求后端：${requested.label} · 加载后确认"
    }
}

/** Injectable native boundary for failure/cancellation tests without a GPU. */
interface LlamaRuntime {
    fun load(path: String, threads: Int, fullPrecisionKv: Boolean, rawCompletion: Boolean, vulkan: Boolean): Long
    fun generate(handle: Long, prompt: String, callback: TokenCallback, expectedResults: Int, constrainJson: Boolean, greedy: Boolean): LongArray
    fun architecture(handle: Long): String
    fun device(handle: Long): String
    fun benchmark(handle: Long, promptTokens: Int, generatedTokens: Int, callback: TokenCallback): DoubleArray
    fun cancel(handle: Long)
    fun free(handle: Long)
}
