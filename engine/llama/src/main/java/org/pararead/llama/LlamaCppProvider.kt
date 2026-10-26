package org.pararead.llama

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.callbackFlow
import org.pararead.core.*
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** All model/context operations share one executor. cancel() only sets the native atomic abort flag. */
class LlamaCppProvider(private val path: String, requestedThreads: Int = 0) : TranslationProvider {
    val threadCount = CpuThreads.resolve(requestedThreads)
    private val executor = Executors.newSingleThreadExecutor { r -> Thread(r, "pararead-llama") }
    private val handle = AtomicLong()
    private val closed = AtomicBoolean()
    private val nativeLock = Any()
    override fun generate(prompt: ModelPrompt) = callbackFlow {
        check(!closed.get()) { "MODEL_CLOSED" }
        require(path.isNotEmpty()) { "请先一键下载或导入 qwen3:1.7b GGUF 模型" }
        val cancelled = AtomicBoolean()
        val stopped = CompletableDeferred<Unit>()
        executor.execute {
            try {
                check(!closed.get() && !cancelled.get())
                if (handle.get() == 0L) handle.set(NativeBridge.load(path, threadCount))
                if (cancelled.get() || closed.get()) return@execute
                val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                var pending = ByteArray(0)
                val output = StringBuilder()
                val input = Qwen3ChatTemplate.render(prompt)
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
                NativeBridge.generate(handle.get(), input, callback)
                check(!cancelled.get()) { "INFERENCE_CANCELLED" }
                check(pending.isEmpty()) { "INCOMPLETE_UTF8_TOKEN" }
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
}
private fun java.nio.charset.CoderResult.throwIfError() { if (isError) throwException() }
interface TokenCallback { fun onBytes(bytes: ByteArray): Boolean }
object NativeBridge {
    init { System.loadLibrary("pararead-llama") }
    external fun load(path: String, threads: Int): Long
    external fun generate(handle: Long, prompt: String, callback: TokenCallback)
    external fun cancel(handle: Long)
    external fun free(handle: Long)
    external fun version(): String
}
