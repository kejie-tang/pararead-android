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
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * All model/context operations share one executor. cancel() only sets the native atomic abort flag.
 * fullPrecisionKv opts into F32 KV storage with Flash Attention disabled; greedy opts into temperature 0.
 * rawCompletion permits the RWKV7 research profile's verbatim, text-only language-label prompt.
 * These are explicit research controls. App model selection does not enable them automatically.
 */
class LlamaCppProvider(private val path: String, requestedThreads: Int = 0, private val singleResult: Boolean = false,
    private val constrainJson: Boolean = true, private val fullPrecisionKv: Boolean = false,
    private val greedy: Boolean = false, private val rawCompletion: Boolean = false) : MeasuredTranslationProvider {
    val threadCount = CpuThreads.resolve(requestedThreads)
    @Volatile override var metrics: GenerationMetrics? = null
        private set
    @Volatile var architecture: String = "unloaded"
        private set
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
                if (handle.get() == 0L) {
                    handle.set(NativeBridge.load(path, threadCount, fullPrecisionKv, rawCompletion))
                    architecture = NativeBridge.architecture(handle.get())
                }
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
                val measured = NativeBridge.generate(handle.get(), input, callback, if (singleResult) 1 else prompt.expectedResults,
                    constrainJson && prompt.outputFormat == ModelOutputFormat.TRANSLATION_JSON, greedy)
                check(!cancelled.get()) { "INFERENCE_CANCELLED" }
                check(pending.isEmpty()) { "INCOMPLETE_UTF8_TOKEN" }
                check(measured.size == 7) { "INVALID_NATIVE_TOKEN_METRICS" }
                metrics = GenerationMetrics(measured[0], measured[1], measured[2], measured[3], measured[4], measured[5], measured[6])
                check(trySendBlocking(ProviderEvent.Complete(output.toString())).isSuccess) { "OUTPUT_CHANNEL_CLOSED" }; close()
            } catch (e: Throwable) { close(e) }
            finally { stopped.complete(Unit) }
        }
        try {
            awaitClose { cancelled.set(true); synchronized(nativeLock) { handle.get().takeIf { it != 0L }?.let { NativeBridge.cancel(it) } } }
        } finally {
            // A cancellation acknowledgement means the executor has actually left load/decode.
            withContext(NonCancellable) { stopped.await() }
        }
    }
    override fun close() {
        if (closed.compareAndSet(false, true)) {
            synchronized(nativeLock) { handle.get().takeIf { it != 0L }?.let { NativeBridge.cancel(it) } }
            executor.execute { synchronized(nativeLock) { handle.getAndSet(0).takeIf { it != 0L }?.let { NativeBridge.free(it) } } }
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
                if (handle.get() == 0L) {
                    handle.set(NativeBridge.load(path, threadCount, fullPrecisionKv, rawCompletion)); architecture = NativeBridge.architecture(handle.get())
                }
                val callback = object : TokenCallback { override fun onBytes(bytes: ByteArray) = !result.isCancelled && !closed.get() }
                if (!result.isCancelled && !closed.get()) result.complete(NativeBridge.benchmark(handle.get(), promptTokens, generatedTokens, callback))
                else result.cancel()
            } catch (e: Throwable) { result.completeExceptionally(e) }
        }
        try { return result.await() }
        finally {
            if (!result.isCompleted) {
                result.cancel()
                synchronized(nativeLock) { handle.get().takeIf { it != 0L }?.let { NativeBridge.cancel(it) } }
            }
        }
    }
}
private fun java.nio.charset.CoderResult.throwIfError() { if (isError) throwException() }
interface TokenCallback { fun onBytes(bytes: ByteArray): Boolean }
object NativeBridge {
    init { System.loadLibrary("pararead-llama") }
    external fun load(path: String, threads: Int, fullPrecisionKv: Boolean, rawCompletion: Boolean): Long
    external fun generate(handle: Long, prompt: String, callback: TokenCallback, expectedResults: Int, constrainJson: Boolean, greedy: Boolean): LongArray
    external fun architecture(handle: Long): String
    external fun systemInfo(): String
    external fun benchmark(handle: Long, promptTokens: Int, generatedTokens: Int, callback: TokenCallback): DoubleArray
    external fun cancel(handle: Long)
    external fun free(handle: Long)
    external fun version(): String
}
