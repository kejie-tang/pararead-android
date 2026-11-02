package org.pararead.android

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import android.view.View
import android.webkit.WebView
import androidx.core.app.NotificationCompat
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.pararead.core.*
import org.pararead.llama.LlamaCppProvider
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * The single admission point for EVERY foreground ReaderHost (and foreground model benchmark).
 * Acquire enterForeground() BEFORE opening a source/provider/Host; release its Lease only AFTER
 * Host.stopAndJoin() and the independent reader scope have actually terminated. A Lease is not
 * lifecycleScope-owned: lifecycle cancellation must not release it before native cleanup finishes.
 *
 * enqueue() rejects while a foreground lease is held. To hand a document to background work,
 * stopAndJoin the foreground Host, release the lease, then enqueue. All APIs use application context.
 * WorkManager's CANCELLED state alone is NOT a native-resource-release acknowledgement.
 */
object BackgroundTranslations {
    const val TAG = "background-translations"
    const val DOCUMENT_TAG_PREFIX = "$TAG:document:"
    const val CREATED_TAG_PREFIX = "whole-created:"
    const val PHASE = "phase"
    const val DOCUMENT_ID = "documentId"
    const val FINGERPRINT = "fingerprint"
    const val TARGET = "targetLanguage"
    const val DONE = "done"
    const val TOTAL = "total"
    const val FAILED_PAGES = "failedPages"
    const val ERROR_CODE = "errorCode"
    const val JOB_ID = "jobId"

    enum class EnqueueResult { ENQUEUED, ALREADY_QUEUED, FOREGROUND_ACTIVE, INVALID_CONFIG, CLEANUP_FAILED }

    private val commands = Mutex()
    private val foreground = Mutex()
    private val background = Mutex()
    private val monitor = Any()
    private var foregroundActive = false
    private var cancellingAll = false
    private var cancellingDocument: String? = null
    private var cleanupFailed = false
    private val workers = mutableSetOf<Registration>()
    private val targets = setOf("zh-CN", "zh-TW", "en", "ja", "ko", "fr", "de", "es", "pt", "ru", "it")

    fun documentTag(documentId: String) = "$DOCUMENT_TAG_PREFIX$documentId"
    fun uniqueName(documentId: String) = "$TAG:work:$documentId"

    private fun createdAt(info: WorkInfo): Long = info.tags.asSequence()
        .filter { it.startsWith(CREATED_TAG_PREFIX) }.mapNotNull { it.removePrefix(CREATED_TAG_PREFIX).toLongOrNull() }
        .maxOrNull() ?: 0L

    /** WorkInfo does not expose inputData. Tags identify both the document and newest attempt. */
    fun latestByDocument(infos: List<WorkInfo>): Map<String, WorkInfo> = buildMap {
        for (info in infos.sortedWith(compareBy<WorkInfo> { createdAt(it) }.thenBy { it.id.toString() })) {
            val tag = info.tags.firstOrNull { it.startsWith(DOCUMENT_TAG_PREFIX) } ?: continue
            put(tag.removePrefix(DOCUMENT_TAG_PREFIX), info)
        }
    }

    /** A newer visible-reader job supersedes old scheduling failures, not durable progress. */
    fun failureFor(info: WorkInfo, currentJob: WholeDocumentJob?): String? {
        if (info.state != WorkInfo.State.FAILED) return null
        val attempt = info.outputData.getLong(JOB_ID, 0L)
        if (currentJob != null && (if (attempt > 0) currentJob.jobId != attempt else currentJob.jobId > createdAt(info))) return null
        return info.outputData.getString(ERROR_CODE) ?: "INTERRUPTED"
    }

    fun workInfos(context: Context, documentId: String? = null): Flow<List<WorkInfo>> =
        WorkManager.getInstance(context.applicationContext)
            .getWorkInfosByTagFlow(documentId?.let(::documentTag) ?: TAG)

    /** KEEP is also enforced by WorkManager, not just this process's command mutex. No secrets in Data. */
    suspend fun enqueue(context: Context, documentId: String): EnqueueResult = commands.withLock {
        require(documentId.isNotBlank() && documentId.length <= 256)
        synchronized(monitor) {
            if (cleanupFailed) return@withLock EnqueueResult.CLEANUP_FAILED
            if (foregroundActive) return@withLock EnqueueResult.FOREGROUND_ACTIVE
        }
        val app = context.applicationContext
        val manager = WorkManager.getInstance(app)
        val existing = withContext(Dispatchers.IO) { manager.getWorkInfosForUniqueWork(uniqueName(documentId)).get() }
        if (existing.any { !it.state.isFinished }) return@withLock EnqueueResult.ALREADY_QUEUED
        val settings = Settings(app)
        val runtime = try { settings.runtimeSnapshot() }
        catch (e: Exception) {
            if (e is CancellationException) throw e
            return@withLock EnqueueResult.INVALID_CONFIG
        }
        val config = runtime.config
        if (!validConfig(config) || !providerAvailable(runtime)) return@withLock EnqueueResult.INVALID_CONFIG
        val fingerprint = try { sha256(config.fingerprint()) }
        catch (e: Exception) {
            if (e is CancellationException) throw e
            return@withLock EnqueueResult.INVALID_CONFIG
        }
        // Monotonic per document even across process restarts or a wall-clock adjustment.
        val created = maxOf(System.currentTimeMillis(), (existing.maxOfOrNull(::createdAt) ?: 0L) + 1L)
        val request = OneTimeWorkRequestBuilder<BackgroundTranslationWorker>()
            .setInputData(workDataOf(DOCUMENT_ID to documentId, FINGERPRINT to fingerprint, TARGET to config.targetLanguage))
            .addTag(TAG).addTag(documentTag(documentId)).addTag("$CREATED_TAG_PREFIX$created").build()
        // Operation.result is a ListenableFuture; never block Main on Future.get().
        withContext(Dispatchers.IO) {
            manager.enqueueUniqueWork(uniqueName(documentId), ExistingWorkPolicy.KEEP, request).result.get()
        }
        val accepted = withContext(Dispatchers.IO) { manager.getWorkInfoById(request.id).get() != null }
        if (accepted) EnqueueResult.ENQUEUED else EnqueueResult.ALREADY_QUEUED
    }

    class Lease internal constructor() {
        private val released = AtomicBoolean()
        /** Unlock waiters only into a poisoned gate; never authorize another native owner. */
        fun failClosed() {
            synchronized(monitor) { cleanupFailed = true }
            release()
        }
        /** Idempotent; the caller must have awaited all foreground Host/native cleanup first. */
        fun release() {
            if (released.compareAndSet(false, true)) {
                synchronized(monitor) { foregroundActive = false }
                foreground.unlock()
            }
        }
    }

    /** Atomically closes background admission, cancels only our tag, then awaits REAL cleanup. */
    suspend fun enterForeground(context: Context): Lease {
        foreground.lock()
        try {
            return commands.withLock {
                synchronized(monitor) {
                    check(!cleanupFailed) { "BACKGROUND_CLEANUP_FAILED" }
                    foregroundActive = true
                }
                withContext(NonCancellable) { cancelAndDrain(context.applicationContext, null) }
                currentCoroutineContext().ensureActive()
                Lease()
            }
        } catch (e: Throwable) {
            synchronized(monitor) { foregroundActive = false }
            foreground.unlock()
            throw e
        }
    }

    /** Also use before deleting a PDF/draft or replacing model files; never substitute cancelAllWork(). */
    suspend fun cancelAndAwait(context: Context, documentId: String? = null) = commands.withLock {
        synchronized(monitor) {
            cancellingAll = documentId == null
            cancellingDocument = documentId
        }
        try {
            // Cancellation of the UI coroutine must not reopen admission halfway through a cancel operation.
            withContext(NonCancellable) { cancelAndDrain(context.applicationContext, documentId) }
        } finally {
            synchronized(monitor) { cancellingAll = false; cancellingDocument = null }
        }
    }

    private suspend fun cancelAndDrain(context: Context, documentId: String?) {
        val manager = WorkManager.getInstance(context)
        withContext(Dispatchers.IO) {
            manager.cancelAllWorkByTag(documentId?.let(::documentTag) ?: TAG).result.get()
        }
        // Every admitted worker registered under this same monitor BEFORE any resource allocation.
        // Workers registering after the snapshot see the closed gate. After gate reopening, a stopped
        // Worker must also pass ensureActive/isStopped before acquiring any source, model or WebView.
        val pending = synchronized(monitor) {
            workers.filter { documentId == null || it.documentId == documentId }.map { it.cleaned }
        }
        // awaitAll is fail-fast; one failed cleanup must NOT skip waiting for the other workers.
        var failed = false
        for (cleanup in pending) {
            try { cleanup.await() } catch (_: Exception) { failed = true }
        }
        synchronized(monitor) { check(!failed && !cleanupFailed) { "BACKGROUND_CLEANUP_FAILED" } }
    }

    internal class Registration(val documentId: String) {
        val cleaned = CompletableDeferred<Unit>()
        var ownsBackground = false
    }

    internal fun register(documentId: String): Registration = synchronized(monitor) {
        Registration(documentId).also { workers.add(it) }
    }

    private fun blocked(documentId: String) = cleanupFailed || foregroundActive || cancellingAll || cancellingDocument == documentId

    internal suspend fun acquire(registration: Registration): Boolean {
        if (synchronized(monitor) { blocked(registration.documentId) }) return false
        background.lock(registration)
        registration.ownsBackground = true
        currentCoroutineContext().ensureActive()
        return synchronized(monitor) { !blocked(registration.documentId) }
    }

    /** A cleanup exception fails admission closed for this process instead of pretending native is gone. */
    internal fun cleaned(registration: Registration, failure: Throwable?) {
        synchronized(monitor) {
            if (failure != null) cleanupFailed = true
            if (failure == null) registration.cleaned.complete(Unit)
            else registration.cleaned.completeExceptionally(IllegalStateException("BACKGROUND_CLEANUP_FAILED"))
            workers.remove(registration)
        }
        // A foreground waiter can now proceed: all shutdown attempts, including native join, are done.
        // On any failure the poisoned gate prevents new foreground AND background resource owners.
        if (registration.ownsBackground) background.unlock(registration)
    }

    internal fun validConfig(config: TranslationConfig) = config.targetLanguage in targets &&
        config.provider in setOf("llama", "api", "mock")

    internal fun providerAvailable(runtime: TranslationRuntimeSnapshot) = when (runtime.config.provider) {
        "mock" -> true
        "api" -> runtime.config.model.isNotBlank() && (runtime.baseUrl.startsWith("https://") || OpenAICompatibleProvider.isLocalHttp(runtime.baseUrl))
        "llama" -> runtime.config.modelHash != "unloaded" && java.io.File(runtime.modelPath).isFile
        else -> false
    }
}

/**
 * ReaderHost integration contract (provided by the foreground integration):
 * - optional backgroundTranslation, onJobChanged and onFatal constructor parameters;
 * - background session.opened starts/resumes the whole job; this Worker MUST NOT command translateAll;
 * - awaitOpened() resolves after session.opened, or throws on bootstrap failure;
 * - onJobChanged fires AFTER the corresponding JobRecord write, including terminal writes;
 * - stopAndJoin(status) persists its own terminal state under its job mutex, closes resources,
 *   cancels/joins its scope, awaits provider termination, and destroys WebView on Main, idempotently.
 * ReaderDao.document(id): DocumentRecord? is the only additional DAO API used here.
 */
class BackgroundTranslationWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private data class Terminal(val job: WholeDocumentJob? = null, val error: String? = null)

    // Distinct IDs for live workers in this process; reserve 4101 for ModelDownloadWorker.
    private val notificationId = nextNotificationId.getAndIncrement()

    companion object {
        private val nextNotificationId = AtomicInteger(4200)
    }

    override suspend fun doWork(): Result {
        val documentId = inputData.getString(BackgroundTranslations.DOCUMENT_ID)
            ?.takeIf { it.isNotBlank() && it.length <= 256 }
            ?: return Result.failure(workDataOf(BackgroundTranslations.ERROR_CODE to "INVALID_INPUT"))
        // Registration precedes even foreground promotion, so cancellation cannot miss a resource owner.
        val registration = BackgroundTranslations.register(documentId)
        var db: ReaderDatabase? = null
        var source: RangeSource? = null
        var provider: TranslationProvider? = null
        var host: ReaderHost? = null
        var webView: WebView? = null
        var hostScope: CoroutineScope? = null
        var reporter: Job? = null
        var title = "ParaRead"
        var target = inputData.getString(BackgroundTranslations.TARGET).orEmpty()
        val latest = AtomicReference<WholeDocumentJob?>()
        val terminal = CompletableDeferred<Terminal>()
        val updates = Channel<WholeDocumentJob>(Channel.CONFLATED)
        var stopStatus = WholeJobStatus.CANCELLED
        var result = Result.failure(data(documentId, target, null, "INTERRUPTED"))
        var cleanupError: Throwable? = null
        try {
            currentCoroutineContext().ensureActive()
            // Promotion MUST precede the global resource wait (queued workers have no WebView/model).
            setForeground(notification(title, documentId, target, null))
            setProgress(data(documentId, target, null, phase = "QUEUED"))
            val fingerprint = inputData.getString(BackgroundTranslations.FINGERPRINT)
            if (fingerprint?.matches(Regex("[0-9a-f]{64}")) != true || target.isEmpty()) {
                result = Result.failure(data(documentId, target, null, "INVALID_INPUT"))
            } else {
                setProgress(data(documentId, target, null, phase = "WAITING"))
                if (!BackgroundTranslations.acquire(registration)) {
                    return Result.failure(data(documentId, target, null, "ADMISSION_CLOSED"))
                }
                // Essential after acquiring the lease: WorkManager may have stopped this worker
                // before doWork registered, or while it was waiting for the previous native session.
                currentCoroutineContext().ensureActive()
                if (isStopped) throw CancellationException("WORK_STOPPED")
                withContext(Dispatchers.IO) { db = ReaderDatabase.open(applicationContext) }
                val dao = requireNotNull(db).reader()
                val document = withContext(Dispatchers.IO) { dao.document(documentId) }
                latest.set(withContext(Dispatchers.IO) { dao.job("whole:$documentId")?.let { runCatching { WholeDocumentJob.parse(it.json) }.getOrNull() } })
                val settings = Settings(applicationContext)
                val runtime = try { settings.runtimeSnapshot().takeIf {
                    BackgroundTranslations.validConfig(it.config) && BackgroundTranslations.providerAvailable(it) &&
                        sha256(it.config.fingerprint()) == fingerprint && it.config.targetLanguage == target
                } } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    null
                }
                val config = runtime?.config
                if (config == null) {
                    result = Result.failure(data(documentId, target, latest.get(), "CONFIG_CHANGED"))
                } else if (document == null) {
                    result = Result.failure(data(documentId, target, null, "DOCUMENT_NOT_FOUND"))
                } else {
                    target = config.targetLanguage
                    title = document.name.take(120)
                    // Re-read the draft only after admission; never retain an enqueue-time file handle.
                    withContext(Dispatchers.IO) { source = RangeSource(document, dao.draft(documentId)) }
                    currentCoroutineContext().ensureActive()
                    if (isStopped) throw CancellationException("WORK_STOPPED")
                    hostScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, _ ->
                        terminal.complete(Terminal(error = "HOST_FATAL"))
                    })
                    // Assign resources INSIDE withContext: cancellation at its return boundary must
                    // not lose a freshly-created resource before finally can close it.
                    withContext(Dispatchers.Main.immediate) {
                        val localized = applicationContext.withAppLanguage()
                        provider = createProvider(settings, requireNotNull(runtime))
                        host = ReaderHost(localized, requireNotNull(source), dao, requireNotNull(hostScope), config, requireNotNull(provider),
                            report = {}, saveRequested = {}, chooseLanguage = {},
                            rendererGone = { terminal.complete(Terminal(error = "RENDERER_GONE")) },
                            backgroundTranslation = true,
                            onJobChanged = { job ->
                                latest.set(job)
                                updates.trySend(job)
                                if (job.status != WholeJobStatus.RUNNING) terminal.complete(Terminal(job))
                            },
                            onFatal = { terminal.complete(Terminal(error = "HOST_FATAL")) })
                        webView = requireNotNull(host).createWebView()
                        // A detached WebView still needs a viewport for PDF.js layout/page discovery.
                        val metrics = localized.resources.displayMetrics
                        val width = metrics.widthPixels.coerceAtLeast(1)
                        val height = metrics.heightPixels.coerceAtLeast(1)
                        requireNotNull(webView).apply {
                            measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                            layout(0, 0, width, height)
                        }
                    }
                    coroutineScope {
                        reporter = launch {
                            for (job in updates) {
                                setProgress(data(documentId, target, job))
                                setForeground(notification(title, documentId, target, job))
                            }
                        }
                        // Only bootstrap has a deadline; slow, low-memory inference is not a failure.
                        withTimeout(90_000) { requireNotNull(host).awaitOpened() }
                        val end = terminal.await()
                        val job = end.job
                        stopStatus = if (end.error != null) WholeJobStatus.ERROR else job?.status ?: WholeJobStatus.ERROR
                        // Persist one final progress snapshot even if the conflated reporter was behind.
                        reporter?.cancelAndJoin()
                        setProgress(data(documentId, target, latest.get()))
                        result = when {
                            end.error != null -> Result.failure(data(documentId, target, latest.get(), end.error))
                            job?.status == WholeJobStatus.COMPLETED && job.failedPages == 0 ->
                                Result.success(data(documentId, target, job))
                            job?.status == WholeJobStatus.COMPLETED -> Result.failure(data(documentId, target, job, "PARTIAL_FAILURE"))
                            job?.status == WholeJobStatus.CANCELLED -> Result.failure(data(documentId, target, job, "CANCELLED"))
                            else -> Result.failure(data(documentId, target, job, "TRANSLATION_FAILED"))
                        }
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            stopStatus = WholeJobStatus.ERROR
            result = Result.failure(data(documentId, target, latest.get(), "OPEN_TIMEOUT"))
        } catch (e: CancellationException) {
            // WorkManager persists CANCELLED itself; the Host persists its OWN terminal job state.
            throw e
        } catch (e: Exception) {
            stopStatus = WholeJobStatus.ERROR
            // Deliberately do not serialize exception messages (URLs, credentials and paths may appear).
            result = Result.failure(data(documentId, target, latest.get(), "TRANSLATION_FAILED"))
        } finally {
            withContext(NonCancellable) {
                suspend fun closeStep(block: suspend () -> Unit) {
                    try { block() }
                    catch (e: Throwable) {
                        if (cleanupError == null) cleanupError = e
                        Log.e("BackgroundTranslations", "Cleanup failed: ${e.javaClass.simpleName}")
                    }
                }
                updates.close()
                closeStep { reporter?.cancelAndJoin() }
                var hostStopped = false
                closeStep {
                    withContext(Dispatchers.Main.immediate) { host?.stopAndJoin(stopStatus) }
                    hostStopped = true
                }
                // Defensive fallback for a Host shutdown error: no failed close may skip later joins.
                if (!hostStopped) closeStep { withContext(Dispatchers.Main.immediate) { host?.close() } }
                closeStep { hostScope?.coroutineContext?.get(Job)?.cancelAndJoin() }
                closeStep { withContext(Dispatchers.IO) { provider?.close() } }
                closeStep { host?.awaitProviderClosed() }
                closeStep { (provider as? LlamaCppProvider)?.closeAndJoin() }
                closeStep { withContext(Dispatchers.IO) { source?.close() } }
                if (!hostStopped) closeStep {
                    withContext(Dispatchers.Main.immediate) {
                        // createWebView may have thrown after assigning Host.webView but before returning.
                        val view = webView ?: host?.let { runCatching { it.webView }.getOrNull() }
                        view?.destroy()
                    }
                }
                closeStep { withContext(Dispatchers.IO) { db?.close() } }
                // Complete cleanup acknowledgement BEFORE unlocking; poison admission on any error.
                BackgroundTranslations.cleaned(registration, cleanupError)
            }
        }
        // No automatic retry loop: the UI offers explicit retry, including partial-page failures.
        return if (cleanupError == null) result else Result.failure(data(documentId, target, latest.get(), "CLEANUP_FAILED"))
    }

    private fun createProvider(settings: Settings, runtime: TranslationRuntimeSnapshot): TranslationProvider = when (runtime.config.provider) {
        "mock" -> MockTranslationProvider()
        // Freeze provider arguments together with its cache identity, before any suspending work.
        "api" -> OpenAICompatibleProvider(runtime.baseUrl, runtime.config.model, runtime.apiKey)
        "llama" -> LlamaCppProvider(runtime.modelPath, runtime.cpuThreads,
            requestedBackend = runtime.backend, onVulkanSession = settings::markVulkanSession)
        else -> error("INVALID_PROVIDER")
    }

    private fun data(documentId: String, target: String, job: WholeDocumentJob?, error: String? = null,
        phase: String = when {
            error != null -> "FAILED"
            job?.status == WholeJobStatus.RUNNING -> "TRANSLATING"
            job != null -> job.status.name
            else -> "WAITING"
        }): Data =
        Data.Builder().putString(BackgroundTranslations.PHASE, phase)
            .putString(BackgroundTranslations.DOCUMENT_ID, documentId)
            .putString(BackgroundTranslations.TARGET, target)
            .putInt(BackgroundTranslations.DONE, job?.done ?: 0)
            .putInt(BackgroundTranslations.TOTAL, job?.total ?: 0)
            .putInt(BackgroundTranslations.FAILED_PAGES, job?.failedPages ?: 0)
            .putLong(BackgroundTranslations.JOB_ID, job?.jobId ?: 0L)
            .apply { if (error != null) putString(BackgroundTranslations.ERROR_CODE, error) }.build()

    private fun notification(title: String, documentId: String, target: String, job: WholeDocumentJob?): ForegroundInfo {
        val localized = applicationContext.withAppLanguage()
        val chinese = localized.resources.configuration.locales[0].language == "zh"
        val channel = "background-translations"
        val manager = applicationContext.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(channel,
            if (chinese) "全文翻译" else "Whole-document translations", NotificationManager.IMPORTANCE_LOW))
        val text = if (job == null) {
            if (chinese) "等待翻译 · $target" else "Waiting to translate · $target"
        } else if (chinese) "${job.done}/${job.total} 页 · 失败 ${job.failedPages} · $target"
        else "${job.done}/${job.total} pages · ${job.failedPages} failed · $target"
        val notification = NotificationCompat.Builder(localized, channel)
            .setSmallIcon(R.drawable.ic_download).setContentTitle("ParaRead · $title")
            .setContentText(text).setSubText(documentId.take(12)).setOngoing(true).setOnlyAlertOnce(true)
            .setProgress(job?.total ?: 0, job?.done ?: 0, job == null)
            .addAction(0, localized.getString(R.string.cancel), WorkManager.getInstance(applicationContext).createCancelPendingIntent(id))
            .build()
        return if (Build.VERSION.SDK_INT >= 29) ForegroundInfo(notificationId, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        else ForegroundInfo(notificationId, notification)
    }
}
