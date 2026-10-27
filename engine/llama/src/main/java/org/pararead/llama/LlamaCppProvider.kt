package org.pararead.llama

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.callbackFlow
import org.pararead.core.*
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private val modelOwnership = Semaphore(1)

/**
 * All model/context operations share one executor. cancel() only sets the native atomic abort flag.
 * fullPrecisionKv opts into F32 KV storage with Flash Attention disabled; greedy opts into temperature 0.
 * rawCompletion permits the RWKV7 research profile's verbatim, text-only language-label prompt.
 * These are explicit research controls. App model selection does not enable them automatically.
 */
class LlamaCppProvider(private val path: String, requestedThreads: Int = 0, private val singleResult: Boolean = false,
    private val constrainJson: Boolean = true, private val fullPrecisionKv: Boolean = false,
    private val greedy: Boolean = false, private val rawCompletion: Boolean = false,
    val requestedBackend: InferenceBackend = InferenceBackend.CPU,
    private val onBackendChanged: (BackendStatus) -> Unit = {},
    private val onVulkanSession: (Boolean) -> Unit = {},
    private val native: LlamaRuntime = NativeBridge) : MeasuredTranslationProvider {
    val threadCount = CpuThreads.resolve(requestedThreads)
    @Volatile override var metrics: GenerationMetrics? = null
        private set
    @Volatile var architecture: String = "unloaded"
        private set
    @Volatile var backendStatus = BackendStatus(requestedBackend)
        private set
    private var useVulkan = requestedBackend == InferenceBackend.VULKAN
    private var ownsModel = false
    private var vulkanSession = false
    private fun publish(status: BackendStatus) { backendStatus = status; onBackendChanged(status) }
    private fun endVulkanSession() {
        if (vulkanSession) { onVulkanSession(false); vulkanSession = false }
    }
    private fun load(cancelled: () -> Boolean = { closed.get() }) {
        if (handle.get() != 0L) return
        // Rotation can create a new provider before the old native load has stopped.
        while (!ownsModel) {
            check(!cancelled()) { "INFERENCE_CANCELLED" }
            ownsModel = modelOwnership.tryAcquire(100, TimeUnit.MILLISECONDS)
        }
        check(!cancelled()) { "INFERENCE_CANCELLED" }
        if (useVulkan && !vulkanSession) { onVulkanSession(true); vulkanSession = true }
        handle.set(native.load(path, threadCount, fullPrecisionKv, rawCompletion, useVulkan))
        architecture = native.architecture(handle.get())
        publish(BackendStatus(requestedBackend, if (useVulkan) InferenceBackend.VULKAN else InferenceBackend.CPU,
            native.device(handle.get()), backendStatus.fallbackReason))
    }
    private fun fallback(error: Exception) {
        synchronized(nativeLock) { handle.getAndSet(0).takeIf { it != 0L }?.let(native::free) }
        useVulkan = false
        endVulkanSession()
        publish(BackendStatus(requestedBackend, fallbackReason = (error.message ?: error.javaClass.simpleName).take(240)))
    }
    private fun canFallback(error: Exception, cancelled: Boolean): Boolean = useVulkan && !closed.get() && !cancelled &&
        error !is kotlinx.coroutines.CancellationException && error.message?.contains("CANCELLED") != true &&
        error.message?.contains("MODEL_TEMPLATE_UNSUPPORTED") != true && error.message?.contains("PROMPT_EXCEEDS_CONTEXT") != true
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "pararead-llama") }
    private val handle = AtomicLong()
    private val closed = AtomicBoolean()
    private val nativeLock = Any()
    override fun generate(prompt: ModelPrompt) = callbackFlow {
        check(!closed.get()) { "MODEL_CLOSED" }
        require(path.isNotEmpty()) { "请先下载或导入支持的 GGUF 模型" }
        val cancelled = AtomicBoolean()
        val stopped = CompletableDeferred<Unit>()
        executor.execute {
            try {
                metrics = null
                check(!closed.get() && !cancelled.get())
                while (true) {
                    try {
                        load { cancelled.get() || closed.get() }
                        if (cancelled.get() || closed.get()) return@execute
                        val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        var pending = ByteArray(0)
                        val output = StringBuilder()
                        val input = if (rawCompletion) {
                            require(architecture == "rwkv7" && prompt.system.isEmpty() && prompt.outputFormat == ModelOutputFormat.TEXT) { "RAW_COMPLETION_REQUIRES_RWKV_TEXT_PROFILE" }
                            prompt.user
                        } else ModelChatTemplate.render(architecture, prompt)
                        val callback = object : TokenCallback {
                            override fun onBytes(bytes: ByteArray): Boolean {
                                if (cancelled.get() || closed.get()) return false
                                val buffer = ByteBuffer.wrap(pending + bytes); val chars = CharBuffer.allocate(buffer.remaining() * 2 + 4)
                                decoder.decode(buffer, chars, false).throwIfError()
                                pending = ByteArray(buffer.remaining()).also { buffer.get(it) }
                                chars.flip(); val delta = chars.toString(); output.append(delta)
                                check(output.length <= 131072) { "OUTPUT_TOO_LARGE" }
                                if (delta.isNotEmpty() && !trySendBlocking(ProviderEvent.Delta(delta)).isSuccess) return false
                                return true
                            }
                        }
                        val measured = native.generate(handle.get(), input, callback, if (singleResult) 1 else prompt.expectedResults,
                            constrainJson && prompt.outputFormat == ModelOutputFormat.TRANSLATION_JSON, greedy)
                        check(!cancelled.get()) { "INFERENCE_CANCELLED" }
                        check(pending.isEmpty()) { "INCOMPLETE_UTF8_TOKEN" }
                        check(measured.size == 7) { "INVALID_NATIVE_TOKEN_METRICS" }
                        metrics = GenerationMetrics(measured[0], measured[1], measured[2], measured[3], measured[4], measured[5], measured[6])
                        check(trySendBlocking(ProviderEvent.Complete(output.toString())).isSuccess) { "OUTPUT_CHANNEL_CLOSED" }; close()
                        break
                    } catch (e: Exception) {
                        if (!canFallback(e, cancelled.get())) throw e
                        fallback(e)
                        check(trySendBlocking(ProviderEvent.Restart).isSuccess) { "OUTPUT_CHANNEL_CLOSED" }
                    }
                }
            } catch (e: Throwable) { close(e) }
            finally { stopped.complete(Unit) }
        }
        try {
            awaitClose { cancelled.set(true); synchronized(nativeLock) { handle.get().takeIf { it != 0L }?.let { native.cancel(it) } } }
        } finally {
            // A cancellation acknowledgement means the executor has actually left load/decode.
            withContext(NonCancellable) { stopped.await() }
        }
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            synchronized(nativeLock) { handle.get().takeIf { it != 0L }?.let { native.cancel(it) } }
            executor.execute {
                try { synchronized(nativeLock) { handle.getAndSet(0).takeIf { it != 0L }?.let { native.free(it) } } }
                finally {
                    try { endVulkanSession() }
                    finally { if (ownsModel) { ownsModel = false; modelOwnership.release() } }
                }
            }
            executor.shutdown()
        }
    }
    /** Do not load another model until all native work and model release have finished. */
    suspend fun closeAndJoin(): Unit = withContext(NonCancellable) {
        close()
        withContext(Dispatchers.IO) {
            while (!executor.awaitTermination(1, TimeUnit.SECONDS)) { /* native load may still be finishing */ }
        }
    }
    suspend fun benchmark(promptTokens: Int = 512, generatedTokens: Int = 128): DoubleArray {
        val result = CompletableDeferred<DoubleArray>()
        check(!closed.get()) { "MODEL_CLOSED" }
        executor.execute {
            try {
                check(!closed.get()) { "MODEL_CLOSED" }
                // Benchmark errors are surfaced, never silently mixed with CPU samples.
                try { load { result.isCancelled || closed.get() } } catch (e: Exception) {
                    if (!canFallback(e, result.isCancelled)) throw e
                    fallback(e); load { result.isCancelled || closed.get() }
                }
                val callback = object : TokenCallback { override fun onBytes(bytes: ByteArray) = !result.isCancelled && !closed.get() }
                if (!result.isCancelled && !closed.get()) {
                    try { result.complete(native.benchmark(handle.get(), promptTokens, generatedTokens, callback)) }
                    catch (e: Exception) {
                        if (!canFallback(e, result.isCancelled)) throw e
                        fallback(e)
                        throw IllegalStateException("GPU 基准失败，已回退 CPU；请选择 CPU 后重新测速，本轮结果已丢弃", e)
                    }
                }
                else result.cancel()
            } catch (e: Throwable) { result.completeExceptionally(e) }
        }
        try { return result.await() }
        finally {
            if (!result.isCompleted) {
                result.cancel()
                synchronized(nativeLock) { handle.get().takeIf { it != 0L }?.let { native.cancel(it) } }
            }
        }
    }
}
private fun java.nio.charset.CoderResult.throwIfError() { if (isError) throwException() }
interface TokenCallback { fun onBytes(bytes: ByteArray): Boolean }
object NativeBridge : LlamaRuntime {
    init { System.loadLibrary("pararead-llama") }
    override external fun load(path: String, threads: Int, fullPrecisionKv: Boolean, rawCompletion: Boolean, vulkan: Boolean): Long
    override external fun generate(handle: Long, prompt: String, callback: TokenCallback, expectedResults: Int, constrainJson: Boolean, greedy: Boolean): LongArray
    override external fun architecture(handle: Long): String
    override external fun device(handle: Long): String
    external fun systemInfo(): String
    override external fun benchmark(handle: Long, promptTokens: Int, generatedTokens: Int, callback: TokenCallback): DoubleArray
    override external fun cancel(handle: Long)
    override external fun free(handle: Long)
    external fun version(): String
}
