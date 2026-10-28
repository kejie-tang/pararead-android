package org.pararead.core

import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.EOFException
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Pinned-size/SHA downloads can resume into the same sibling file. Publication is separate. */
class ModelDownload(private val client: OkHttpClient) {
    suspend fun fetch(asset: ModelAsset, part: File, progress: suspend (Long) -> Unit = {}): Unit = withContext(Dispatchers.IO) {
        require(asset.bytes > 24 && asset.sha256.matches(Regex("[a-f0-9]{64}")))
        if (part.length() > asset.bytes) check(part.delete())
        val offset = part.length()
        if (offset == asset.bytes) return@withContext
        val request = Request.Builder().url(asset.url).header("Accept-Encoding", "identity").apply {
            if (offset > 0) header("Range", "bytes=$offset-")
        }.build()
        val call = client.newCall(request)
        coroutineScope {
            // Cancel a blocking body read too, not only the initial asynchronous request.
            val cancellation = launch { try { awaitCancellation() } finally { call.cancel() } }
            try {
                call.response().use { response ->
                    if (response.code in 500..599 || response.code in setOf(408, 429)) throw IOException("HTTP ${response.code}")
                    require(response.code == 200 || response.code == 206) { "下载失败（HTTP ${response.code}）" }
                    val append = response.code == 206
                    if (append) {
                        val range = Regex("bytes (\\d+)-(\\d+)/(\\d+)").matchEntire(response.header("Content-Range").orEmpty())
                        require(range != null && range.groupValues[1].toLong() == offset &&
                            range.groupValues[2].toLong() == asset.bytes - 1 && range.groupValues[3].toLong() == asset.bytes) {
                            "服务器续传范围不匹配，请重试下载"
                        }
                    }
                    var total = if (append) offset else 0L // A server ignoring Range safely restarts this file.
                    progress(total)
                    val body = requireNotNull(response.body) { "下载响应为空" }
                    FileOutputStream(part, append).use { out ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(262144)
                            while (true) {
                                ensureActive()
                                val n = input.read(buffer); if (n < 0) break
                                require(total + n <= asset.bytes) { "下载文件大小超出预期，请重试" }
                                out.write(buffer, 0, n); total += n; progress(total)
                            }
                        }
                        out.fd.sync()
                    }
                    if (total != asset.bytes) throw EOFException("网络中断，保留进度等待续传")
                }
            } catch (e: IOException) { ensureActive(); throw e }
            finally { cancellation.cancel() }
        }
    }
    private suspend fun Call.response(): Response = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                continuation.resume(response) { _, value, _ -> value.close() }
            }
        })
    }
}
