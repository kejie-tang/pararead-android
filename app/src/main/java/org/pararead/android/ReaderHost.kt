package org.pararead.android

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import android.webkit.*
import androidx.webkit.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.pararead.core.*
import java.io.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

class ReaderHost(
    private val context: Context, val source: RangeSource, private val dao: ReaderDao,
    private val scope: CoroutineScope, private val config: TranslationConfig, private val provider: TranslationProvider,
    private val report: (String) -> Unit, private val saveRequested: () -> Unit, private val chooseLanguage: () -> Unit,
    private val rendererGone: () -> Unit,
) : Closeable {
    val sessionId = UUID.randomUUID().toString()
    private var proxy: JavaScriptReplyProxy? = null
    private var ready = false
    private var closed = false
    private var epoch = 0
    private val configurationRevision = 1
    private val started = SystemClock.elapsedRealtime()
    private val translationQueue = TranslationQueue(scope, RoomTranslationCache(dao)) { provider }
    private val inflight = mutableSetOf<String>()
    private val pageChunks = mutableMapOf<Int, Array<String?>>()
    private var reading = JSONObject()
    private val jobMutex = Mutex()
    private val jobKey = "whole:${source.document.id}"
    private var wholeJob: WholeDocumentJob? = null
    private var pageCount = 0
    private var wholeJobStarted = false
    private var autoSave: Job? = null
    private var exportTarget: Uri? = null
    private var exportTemp: File? = null
    private var saveId: String? = null
    private var expectedSaveLength = 0L
    private var saveLength = 0L
    private val assetLoader = WebViewAssetLoader.Builder().addPathHandler("/reader/", WebViewAssetLoader.AssetsPathHandler(context)).build()
    lateinit var webView: WebView
        private set

    @Suppress("SetJavaScriptEnabled")
    fun createWebView(): WebView {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            report("请更新 Android System WebView 后重新打开文档")
            webView = WebView(context).apply { loadData("<p>Please update Android System WebView.</p>", "text/html", "utf-8") }
            return webView
        }
        webView = WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = false
            settings.allowContentAccess = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.setSupportZoom(false)
            settings.mediaPlaybackRequiresUserGesture = true
            setBackgroundColor(0xffeeeeee.toInt())
            WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
            webChromeClient = object : WebChromeClient() {
                override fun onConsoleMessage(message: ConsoleMessage): Boolean {
                    Log.d("ParaReadWeb", "${message.messageLevel()}: ${message.message().take(700)} @${message.lineNumber()}")
                    return true
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val uri = request.url
                    if (uri.scheme != "https" || uri.host != "appassets.androidplatform.net") return errorResponse(403, "Forbidden")
                    if (uri.path == "/documents/${source.token}/ranges" && !closed && ready && request.method == "GET") {
                        return try {
                            val begin = uri.getQueryParameter("begin")!!.toLong(); val end = uri.getQueryParameter("end")!!.toLong()
                            val bytes = source.read(begin, end)
                            WebResourceResponse("application/octet-stream", null, 200, "OK", mapOf("Content-Length" to bytes.size.toString(), "Cache-Control" to "no-store", "X-Content-Type-Options" to "nosniff"), ByteArrayInputStream(bytes))
                        } catch (_: Exception) { errorResponse(416, "Invalid range") }
                    }
                    // AssetsPathHandler appends the matched suffix to the root assets directory.
                    return assetLoader.shouldInterceptRequest(Uri.parse("https://appassets.androidplatform.net/reader/reader/${uri.path?.removePrefix("/reader/")}")) ?: errorResponse(404, "Not found")
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    if (request.hasGesture()) openExternal(request.url.toString())
                    return true
                }
                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                    close(); view.destroy(); report("阅读器进程已恢复，请重新打开文档"); rendererGone(); return true
                }
                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) report("阅读器加载失败：${error.description}")
                }
            }
        }
        WebViewCompat.addWebMessageListener(webView, "ParaReadNative", setOf("https://appassets.androidplatform.net")) { _, message, origin, mainFrame, reply ->
            if (!mainFrame || origin.toString().trimEnd('/') != "https://appassets.androidplatform.net" || closed) return@addWebMessageListener
            proxy = reply
            val raw = message.data ?: return@addWebMessageListener
            scope.launch {
                var envelope: Envelope? = null
                try {
                    envelope = Envelope.parse(raw)
                    if (envelope.type != "reader.ready") {
                        require(ready && envelope.sessionId == sessionId && envelope.documentId == source.document.id && envelope.documentRevision == source.revision && envelope.configRevision == configurationRevision) { "STALE_SESSION" }
                    }
                    handle(envelope)
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    val code = (e.message ?: "INVALID_MESSAGE").take(512)
                    envelope?.let { send(it.reply("protocol.error", JSONObject().put("code", code))) }
                    Log.w("ParaReadHost", "Bridge rejected: $code")
                }
            }
        }
        scope.launch {
            reading = withContext(Dispatchers.IO) { dao.reading(source.document.id)?.let { JSONObject(it.json) } ?: JSONObject() }
            wholeJob = withContext(Dispatchers.IO) { dao.job(jobKey)?.let { runCatching { WholeDocumentJob.parse(it.json) }.getOrNull() } }
            if (!closed) webView.loadUrl("https://appassets.androidplatform.net/reader/index.html")
        }
        return webView
    }
    private fun errorResponse(status: Int, text: String) = WebResourceResponse("text/plain", "utf-8", status, text, emptyMap(), ByteArrayInputStream(text.toByteArray()))
    private fun base(type: String, payload: JSONObject = JSONObject(), id: String = "host-${UUID.randomUUID()}") =
        Envelope(type, sessionId, source.document.id, source.revision, id, epoch, configurationRevision, payload)
    private suspend fun send(envelope: Envelope) = withContext(Dispatchers.Main.immediate) {
        if (!closed && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            val text = envelope.json().toString()
            require(text.toByteArray().size <= Envelope.MAX_BYTES) { "REPLY_TOO_LARGE" }
            proxy?.postMessage(text)
        }
    }
    private suspend fun handle(m: Envelope) {
        val p = m.payload
        when (m.type) {
            "reader.ready" -> {
                require(!ready) { "ALREADY_OPEN" }; ready = true
                send(base("session.open", JSONObject().put("token", source.token).put("length", source.length).put("reading", reading)
                    .put("debug", BuildConfig.DEBUG).put("settings", JSONObject().put("targetLanguage", config.targetLanguage).put("translateRange", Settings(context).translateRange).put("selectOnHover", true))))
            }
            "session.opened" -> {
                pageCount = p.getInt("pages"); require(pageCount > 0)
                report("${source.document.name} · $pageCount 页 · ${if (config.provider == "mock") "模拟翻译" else config.model}")
                metric("open_ms", (SystemClock.elapsedRealtime() - started).toDouble())
                jobMutex.withLock {
                    if (wholeJob?.matches(source.revision, config, pageCount) != true) wholeJob = null
                    if (wholeJob?.status == WholeJobStatus.RUNNING) startWholeJob()
                    else if (wholeJob?.status in setOf(WholeJobStatus.CANCELLED, WholeJobStatus.ERROR)) reportJob()
                }
            }
            "session.error" -> report(p.optString("message").take(600))
            "page.visible" -> { epoch = maxOf(epoch, m.viewportEpoch); translationQueue.viewport(epoch) }
            "translation.request" -> translate(m)
            "request.cancel" -> { translationQueue.cancel(p.getString("requestId")); pageChunks.clear() }
            "page.cacheGet" -> {
                val value = withContext(Dispatchers.IO) { dao.page(source.revision, p.getInt("page")) }
                val payload = JSONObject().put("page", value?.let { JSONObject(it.json) } ?: JSONObject.NULL)
                if (payload.toString().toByteArray().size > 60000) send(m.reply("page.cached", JSONObject().put("page", JSONObject.NULL)))
                else send(m.reply("page.cached", payload))
            }
            "page.textChunk" -> savePage(m)
            "reading.save" -> {
                p.keys().forEach { reading.put(it, p.get(it)) }
                val snapshot = reading.toString()
                withContext(Dispatchers.IO) { dao.reading(ReadingRecord(source.document.id, snapshot)) }
                send(m.reply("reading.saved", JSONObject()))
            }
            "annotation.changed" -> {
                report("批注正在保存到本地草稿…")
                autoSave?.cancel(); autoSave = scope.launch { delay(1200); requestSave(null) }
            }
            "document.save" -> if (p.has("incrementalLength")) {
                require(saveId == null) { "SAVE_BUSY" }
                expectedSaveLength = p.getLong("incrementalLength"); require(expectedSaveLength in 0..16L * 1024 * 1024) { "SAVE_TOO_LARGE" }
                saveLength = 0; saveId = UUID.randomUUID().toString()
                exportTemp = File(context.cacheDir, "$saveId.suffix").apply { createNewFile() }
                send(m.reply("save.started", JSONObject().put("saveId", saveId)))
            } else saveRequested()
            "save.chunk" -> {
                require(p.getString("saveId") == saveId && p.getLong("offset") == saveLength) { "SAVE_SEQUENCE_ERROR" }
                val bytes = Base64.decode(p.getString("data"), Base64.DEFAULT)
                require(bytes.size <= 24576 && saveLength + bytes.size <= expectedSaveLength) { "SAVE_CHUNK_TOO_LARGE" }
                withContext(Dispatchers.IO) { FileOutputStream(exportTemp!!, true).use { it.write(bytes) } }
                saveLength += bytes.size
                send(m.reply("save.progress", JSONObject().put("received", saveLength)))
            }
            "save.end" -> finishSave(m)
            "external.open" -> openExternal(p.getString("url"))
            "settings.chooseLanguage" -> chooseLanguage()
            "job.progress", "job.finished" -> {
                jobMutex.withLock {
                    val job = requireNotNull(wholeJob) { "UNKNOWN_JOB" }
                    require(p.getLong("jobId") == job.jobId && p.getInt("total") == job.total) { "STALE_JOB" }
                    if (m.type == "job.finished") wholeJobStarted = false
                    if (job.status == WholeJobStatus.RUNNING) {
                        val updated = if (m.type == "job.progress") {
                            val done = p.getInt("done")
                            if (done == job.done) job else job.progress(done, p.getBoolean("failed"))
                        } else {
                            val status = WholeJobStatus.valueOf(p.getString("status"))
                            require(status != WholeJobStatus.RUNNING)
                            job.finish(status)
                        }
                        persistJob(updated); reportJob()
                    }
                    send(m.reply("job.saved", JSONObject().put("status", wholeJob!!.status.name)))
                }
            }
            "telemetry.event" -> metric(p.optString("name").take(80), p.optDouble("value", 0.0))
        }
    }
    private suspend fun translate(m: Envelope) {
        require(m.requestId !in inflight) { "DUPLICATE_REQUEST" }
        val rows = m.payload.getJSONArray("sentences")
        require(rows.length() in 1..40) { "INVALID_SENTENCE_COUNT" }
        val ids = mutableSetOf<String>(); var bytes = 0
        val sentences = (0 until rows.length()).map { i ->
            val s = rows.getJSONObject(i); val id = s.getString("id"); val text = s.getString("text"); val group = s.getString("group")
            require(id.matches(Regex("p[1-9][0-9]*-b[0-9]+-s[0-9]+")) && id.length <= 128 && ids.add(id)) { "INVALID_SENTENCE_ID" }
            val size = text.toByteArray().size; require(size in 1..16384); bytes += size
            require(group.length <= 128)
            SourceSentence(id, text, group)
        }
        require(bytes <= 32768) { "SOURCE_TOO_LARGE" }
        val priority = m.payload.optInt("priority", 1); require(priority in 0..3)
        if (priority == 3) {
            val job = requireNotNull(wholeJob) { "UNKNOWN_JOB" }
            require(job.status == WholeJobStatus.RUNNING && m.payload.getLong("jobId") == job.jobId && m.payload.getInt("page") == job.nextPage) { "STALE_JOB" }
        }
        if (priority in 1..2) require(m.viewportEpoch >= epoch) { "STALE_VIEWPORT" }
        inflight += m.requestId
        val terminal = AtomicBoolean(false); var seq = 0; val start = SystemClock.elapsedRealtime()
        val work = TranslationWork(m.requestId, m.viewportEpoch, priority, sentences, config,
            committed = { s, text, key -> send(m.reply("translation.committed", JSONObject().put("sequence", seq++).put("items", JSONArray().put(JSONObject().put("id", s.id).put("translation", text).put("contentKey", key))))) },
            finished = { failures, cancelled ->
                if (terminal.compareAndSet(false, true)) {
                    inflight -= m.requestId
                    send(m.reply("translation.finished", JSONObject().put("cancelled", cancelled).put("failures", JSONArray().apply { failures.forEach { put(JSONObject().put("id", it.first).put("message", it.second)) } })))
                    metric("translation_ms", (SystemClock.elapsedRealtime() - start).toDouble())
                }
            },
            delta = { s, text -> if (text.toByteArray().size < 48000) send(m.reply("translation.delta", JSONObject().put("sequence", seq++).put("items", JSONArray().put(JSONObject().put("id", s.id).put("translation", text))))) })
        if (!translationQueue.submit(work)) { inflight -= m.requestId; error("QUEUE_FULL") }
    }
    private suspend fun savePage(m: Envelope) {
        val p = m.payload; val page = p.getInt("page"); val count = p.getInt("chunkCount"); val index = p.getInt("chunkIndex")
        require(page > 0 && count in 1..64 && index in 0 until count && p.getString("data").length <= 5000)
        require(pageChunks.size < 8 || pageChunks.containsKey(page)) { "PAGE_QUEUE_FULL" }
        val chunks = pageChunks.getOrPut(page) { arrayOfNulls(count) }; require(chunks.size == count)
        chunks[index] = p.getString("data")
        if (chunks.all { it != null }) {
            val json = chunks.joinToString("") { it!! }; require(JSONObject(json).getInt("page") == page)
            withContext(Dispatchers.IO) { dao.page(PageRecord(source.revision, page, json = json)) }; pageChunks.remove(page)
        }
    }
    private suspend fun persistJob(job: WholeDocumentJob) {
        withContext(Dispatchers.IO) { dao.job(JobRecord(jobKey, source.document.id, job.json().toString(), job.status.name)) }
        wholeJob = job
    }
    private fun reportJob() {
        val job = wholeJob ?: return
        val state = when (job.status) {
            WholeJobStatus.RUNNING -> "全篇翻译"
            WholeJobStatus.CANCELLED -> "全篇已取消，点击全篇可继续"
            WholeJobStatus.ERROR -> "全篇已中断，点击全篇可重试"
            WholeJobStatus.COMPLETED -> "全篇已处理完成"
        }
        report("$state：${job.done} / ${job.total} 页 · 成功 ${job.successfulPages} · 含失败句 ${job.failedPages}")
    }
    /** Called under jobMutex, after the reader has installed its message listener. */
    private suspend fun startWholeJob() {
        val job = wholeJob ?: return
        wholeJobStarted = true
        send(base("reader.command", JSONObject().put("type", "translateAll").put("jobId", job.jobId).put("startPage", job.nextPage)))
        reportJob()
    }
    fun command(type: String) { scope.launch {
      try {
        if (type == "translateAll" || type == "cancelTranslateAll") jobMutex.withLock {
            if (pageCount == 0) { report("请等待文档打开后再翻译"); return@withLock }
            if (type == "translateAll") {
                if (wholeJobStarted) { reportJob(); return@withLock }
                val old = wholeJob
                val id = maxOf(System.currentTimeMillis(), (old?.jobId ?: 0L) + 1)
                val next = if (old != null && old.status != WholeJobStatus.COMPLETED) old.copy(jobId = id, status = WholeJobStatus.RUNNING)
                    else WholeDocumentJob(id, source.revision, sha256(config.fingerprint()), pageCount)
                persistJob(next); startWholeJob()
            } else {
                try {
                    wholeJob?.takeIf { it.status == WholeJobStatus.RUNNING }?.let { persistJob(it.finish(WholeJobStatus.CANCELLED)); reportJob() }
                } finally {
                    translationQueue.cancelAll()
                    send(base("reader.command", JSONObject().put("type", type)))
                }
            }
        } else send(base("reader.command", JSONObject().put("type", type)))
      } catch (e: CancellationException) { throw e }
      catch (e: Exception) { report("操作失败，进度未确认保存：${e.message?.take(200)}") }
    } }
    fun requestSave(target: Uri?) { scope.launch { if (target != null) exportTarget = target; send(base("document.save")) } }
    private suspend fun finishSave(m: Envelope) {
        require(m.payload.getString("saveId") == saveId && m.payload.getLong("length") == saveLength && saveLength == expectedSaveLength) { "INCOMPLETE_SAVE" }
        val suffix = requireNotNull(exportTemp)
        try {
            withContext(Dispatchers.IO) {
                FileOutputStream(suffix, true).use { it.fd.sync() }
                // Always derive the suffix from this session's immutable baseline; repeated saves cannot double append.
                val draft = File(context.filesDir, "draft-${UUID.randomUUID()}.suffix")
                FileOutputStream(draft).use { output ->
                    var pos = source.baseLength
                    while (pos < source.length) { val data = source.read(pos, minOf(source.length, pos + 262144)); output.write(data); pos += data.size }
                    suffix.inputStream().use { it.copyTo(output, 262144) }; output.fd.sync()
                }
                val old = dao.draft(source.document.id)
                dao.draft(DraftRecord(source.document.id, source.document.revision, draft.absolutePath, draft.length()))
                // Old snapshots may still be open by RangeSource; unlinking is safe on Android's file descriptors.
                old?.let { File(it.path).delete() }
                exportTarget?.let { uri ->
                    context.contentResolver.openFileDescriptor(uri, "rwt")!!.use { pfd -> FileOutputStream(pfd.fileDescriptor).use { output -> source.copyTo(output, suffix); output.fd.sync() } }
                }
            }
            send(m.reply("save.finished", JSONObject().put("draftPersisted", true).put("exported", exportTarget != null)))
            report(if (exportTarget != null) "批注 PDF 已另存" else "批注草稿已保存，可恢复或另存 PDF")
        } finally { exportTarget = null; suffix.delete(); exportTemp = null; saveId = null }
    }
    private suspend fun metric(name: String, value: Double) {
        if (value.isFinite()) withContext(Dispatchers.IO) { dao.performance(PerformanceRecord(documentId = source.document.id, name = name, value = value)) }
    }
    private fun openExternal(url: String) {
        val uri = Uri.parse(url)
        if (uri.scheme in setOf("https", "http", "mailto")) runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri)) }
    }
    override fun close() {
        if (closed) return
        closed = true; translationQueue.cancelAll(); autoSave?.cancel(); pageChunks.clear()
        exportTemp?.delete(); source.close(); provider.close()
    }
    suspend fun awaitProviderClosed() { (provider as? org.pararead.llama.LlamaCppProvider)?.closeAndJoin() }
}
