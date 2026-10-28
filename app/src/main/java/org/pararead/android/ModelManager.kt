package org.pararead.android

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.os.StatFs
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import org.json.JSONObject
import androidx.core.content.edit
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.pararead.core.*
import java.io.File
import java.util.UUID

enum class ModelPhase { IDLE, DOWNLOADING, PAUSED, VERIFYING, IMPORTING, SWITCHING, DELETING, ERROR }
data class ModelState(val installed: InstalledModel? = null, val phase: ModelPhase = ModelPhase.IDLE,
    val completed: Long = 0, val total: Long = DefaultModel.asset.bytes, val message: String = "",
    val models: List<SavedModel> = emptyList(), val target: ModelAsset? = null, val canRetry: Boolean = false) {
    val busy get() = phase !in setOf(ModelPhase.IDLE, ModelPhase.ERROR)
    val cancellable get() = phase in setOf(ModelPhase.DOWNLOADING, ModelPhase.PAUSED, ModelPhase.VERIFYING, ModelPhase.IMPORTING)
}

class ModelViewModel(app: Application) : AndroidViewModel(app) {
    val manager = ModelManager(app, viewModelScope)
}

/** WorkManager owns new transfers; the legacy system-download monitor completes upgrades in place. */
class ModelManager(context: Context, private val scope: CoroutineScope, val asset: ModelAsset = DefaultModel.asset) {
    private val app = context.applicationContext
    private val settings = Settings(app)
    private val prefs = app.getSharedPreferences("model-download", Context.MODE_PRIVATE)
    private var target: ModelAsset = runCatching { JSONObject(prefs.getString("target", "")!!).let {
        ModelAsset(it.getString("name"), it.getString("url"), it.getLong("bytes"), it.getString("sha256"),
            it.optString("license"), it.optString("repository"), it.optString("revision"))
    } }.getOrDefault(asset)
    private val downloads = app.getSystemService(DownloadManager::class.java)
    private val files = ModelFiles(File(app.filesDir, "models"))
    private val library = ModelLibrary(app, files.directory, settings)
    private val downloadDirectory = requireNotNull(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS))
    private val staging get() = File(downloadDirectory, prefs.getString("file", "qwen3-download.part")!!)
    private val mutable = MutableStateFlow(ModelState(installed(),
        phase = if (prefs.getString("error", "").isNullOrEmpty()) ModelPhase.IDLE else ModelPhase.ERROR,
        message = prefs.getString("error", "") ?: "", models = library.all(), target = if (prefs.getBoolean("retryDownload", false)) target else null,
        canRetry = !prefs.getString("error", "").isNullOrEmpty() && prefs.getBoolean("retryDownload", false)))
    val state = mutable.asStateFlow()
    private var work: Job? = null
    private var downloadId: Long
        get() = prefs.getLong("id", 0)
        set(value) { prefs.edit(commit = true) { putLong("id", value) } }

    private val workerId get() = prefs.getString("work", null)?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    private val workers get() = WorkManager.getInstance(app)
    private fun partial() = prefs.getString("partial", null)?.takeIf { it == File(it).name && it.endsWith(".part") }?.let { File(files.directory, it) }

    init {
        if (workerId != null && prefs.getString("error", "").isNullOrEmpty()) monitorWorker(workerId!!, target)
        else if (downloadId > 0) monitor()
        else if (state.value.installed?.sha256 == target.sha256 && state.value.message == "下载任务已移除，请重试") {
            prefs.edit { remove("error"); remove("retryDownload") }; update(ModelPhase.IDLE, "模型已就绪，思考模式已关闭")
        }
    }
    private fun installed() = settings.modelPath.takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isFile }
        ?.let { InstalledModel(it.absolutePath, settings.modelHash, it.length()) }
    private fun update(phase: ModelPhase, message: String, completed: Long = 0) {
        val retry = phase == ModelPhase.ERROR && prefs.getBoolean("retryDownload", false)
        val downloading = phase in setOf(ModelPhase.DOWNLOADING, ModelPhase.PAUSED, ModelPhase.VERIFYING)
        mutable.value = ModelState(installed(), phase, completed, target.bytes, message, library.all(),
            if (downloading || retry) target else null, retry)
    }
    private fun clearTransfer() {
        val temp = staging
        if (downloadId > 0) downloads.remove(downloadId)
        val part = partial()
        prefs.edit(commit = true) { putLong("id", 0); remove("file"); remove("work"); remove("partial"); remove("partialHash") }
        temp.delete(); part?.delete()
    }
    private fun failure(e: Exception, retryDownload: Boolean = false) {
        val message = e.message ?: "模型操作失败，请重试"
        prefs.edit { putString("error", message); putBoolean("retryDownload", retryDownload) }; update(ModelPhase.ERROR, message)
    }
    fun download(selected: ModelAsset = asset) {
        if (state.value.busy) return
        if (library.all().any { it.model.sha256 == selected.sha256 }) { select(selected.sha256); return }
        target = selected
        persistTarget()
        update(ModelPhase.DOWNLOADING, "正在准备下载…")
        work?.cancel()
        work = scope.launch {
            try {
                require(selected.bytes > 24 && selected.bytes < Long.MAX_VALUE / 3 && selected.sha256.matches(Regex("[a-f0-9]{64}"))) { "模型大小或 SHA-256 无效" }
                require(selected.url.startsWith("https://") || (BuildConfig.DEBUG && selected.url.toUri().host in setOf("127.0.0.1", "localhost"))) { "模型下载地址必须使用 HTTPS" }
                val previous = workerId
                if (previous != null) withContext(Dispatchers.IO) { workers.cancelWorkById(previous).result.get(30, TimeUnit.SECONDS) }
                val part = withContext(Dispatchers.IO) { ModelTransferLocks.forDirectory(files.directory.path).withLock {
                    val existing = partial()
                    val reusable = existing?.takeIf { prefs.getString("partialHash", "") == selected.sha256 && it.length() <= selected.bytes }
                    if (reusable == null) existing?.delete()
                    val file = reusable ?: File(files.directory, "model-${UUID.randomUUID()}.part")
                    val required = selected.bytes - file.length() + 67108864L
                    require(StatFs(files.directory.path).availableBytes >= required) { "空间不足，下载约需 ${required / 1048576 + 1} MiB 可用空间" }
                    file
                } }
                val request = OneTimeWorkRequestBuilder<ModelDownloadWorker>()
                    .setInputData(workDataOf("directory" to files.directory.absolutePath, "file" to part.name,
                        "name" to selected.name, "url" to selected.url, "bytes" to selected.bytes, "sha256" to selected.sha256))
                    .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 10, TimeUnit.SECONDS).build()
                prefs.edit(commit = true) {
                    putString("work", request.id.toString()); putString("partial", part.name); putString("partialHash", selected.sha256)
                    remove("error"); remove("retryDownload")
                }
                withContext(Dispatchers.IO) {
                    workers.enqueueUniqueWork("model-${files.directory.canonicalPath}", ExistingWorkPolicy.REPLACE, request).result.get(30, TimeUnit.SECONDS)
                }
                monitorWorker(request.id, selected)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure(e, retryDownload = true) }
        }
    }
    private fun monitorWorker(id: UUID, asset: ModelAsset) {
        update(ModelPhase.DOWNLOADING, "等待下载…")
        work = scope.launch {
            try {
                workers.getWorkInfoByIdFlow(id).first { info ->
                    if (workerId != id) return@first true
                    if (info == null) return@first false
                    val count = info.progress.getLong("completed", 0)
                    when (info.state) {
                        WorkInfo.State.SUCCEEDED -> {
                            val data = info.outputData
                            val model = InstalledModel(requireNotNull(data.getString("path")), requireNotNull(data.getString("sha256")), data.getLong("bytes", 0))
                            require(model.sha256 == asset.sha256 && model.bytes == asset.bytes && File(model.path).isFile) { "模型文件不完整，请重试下载" }
                            activate(model, asset.name, asset)
                        }
                        WorkInfo.State.FAILED -> failure(IllegalStateException(info.outputData.getString("error") ?: "模型下载失败，请重试"), retryDownload = true)
                        WorkInfo.State.CANCELLED -> {
                            withContext(Dispatchers.IO) { ModelTransferLocks.forDirectory(files.directory.path).withLock { clearTransfer() } }
                            prefs.edit { remove("error"); remove("retryDownload") }; update(ModelPhase.IDLE, "已取消")
                        }
                        WorkInfo.State.RUNNING -> if (info.progress.getString("stage") == "verify") update(ModelPhase.VERIFYING, "正在校验模型…", count)
                            else update(ModelPhase.DOWNLOADING, "正在下载 ${asset.name}…", count)
                        else -> update(ModelPhase.PAUSED, if (info.runAttemptCount > 0) "网络中断，等待自动续传…" else "等待网络或系统调度…", partial()?.length() ?: 0)
                    }
                    info.state.isFinished
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure(e, retryDownload = true) }
        }
    }
    private fun monitor() {
        val asset = target // A restored monitor verifies the persisted selection, not the default model.
        update(ModelPhase.DOWNLOADING, "等待下载…")
        work = scope.launch {
            try {
                while (isActive) {
                    val snapshot = withContext(Dispatchers.IO) {
                        downloads.query(DownloadManager.Query().setFilterById(downloadId))?.use { cursor ->
                            if (!cursor.moveToFirst()) null else Triple(
                                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)),
                                cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON)))
                        }
                    }
                    if (snapshot == null) {
                        // Another monitor may have already verified/published the same task and removed its system row.
                        val ready = installed()?.takeIf { it.sha256 == asset.sha256 && it.bytes == asset.bytes }
                        if (ready != null) {
                            activate(ready, asset.name, asset)
                            return@launch
                        }
                        error("下载任务已移除，请重试")
                    }
                    require(snapshot.second <= asset.bytes) { "下载文件大小超出预期，请重试" }
                    when (snapshot.first) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            val ready = installed()?.takeIf { it.sha256 == asset.sha256 && it.bytes == asset.bytes }
                            if (ready != null) { activate(ready, asset.name, asset); return@launch }
                            update(ModelPhase.VERIFYING, "正在校验模型…")
                            val model = withContext(Dispatchers.IO) { ParcelFileDescriptor.AutoCloseInputStream(downloads.openDownloadedFile(downloadId)).use { input ->
                                files.install(input, asset) { bytes -> update(ModelPhase.VERIFYING, "正在校验模型…", bytes) }
                            } }
                            activate(model, asset.name, asset)
                            return@launch
                        }
                        DownloadManager.STATUS_FAILED -> error("下载失败（${snapshot.third}），请检查网络或存储空间后重试")
                        DownloadManager.STATUS_PAUSED -> update(ModelPhase.PAUSED, "下载暂停，等待网络或系统重试…", snapshot.second)
                        else -> update(ModelPhase.DOWNLOADING, "正在下载 ${asset.name}…", snapshot.second)
                    }
                    delay(500)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { runCatching { clearTransfer() }; failure(e, retryDownload = true) }
        }
    }
    private suspend fun activate(model: InstalledModel, name: String, source: ModelAsset? = null) {
        // Publication and preferences must finish together even if monitoring is then cancelled.
        withContext(NonCancellable + Dispatchers.IO) {
            library.remember(SavedModel(model, name, source?.license ?: "", source?.repository ?: "", source?.revision ?: ""))
            settings.installModel(model.path, model.sha256, name, source?.license ?: "", source?.repository ?: "", source?.revision ?: "")
            // Do not announce IDLE until the persistent transfer record and second weight copy are gone.
            clearTransfer()
        }
        prefs.edit { remove("error"); remove("retryDownload") }; update(ModelPhase.IDLE, "模型已就绪，思考模式已关闭")
    }
    fun import(uri: Uri) {
        if (state.value.busy) return
        update(ModelPhase.IMPORTING, "正在导入 GGUF…")
        work = scope.launch {
            try {
                val name = withContext(Dispatchers.IO) {
                    runCatching { app.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                        if (it.moveToFirst()) it.getString(0) else null
                    } }.getOrNull()?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "本机 GGUF"
                }
                val model = withContext(Dispatchers.IO) { requireNotNull(app.contentResolver.openInputStream(uri)).use { input ->
                    files.install(input) { update(ModelPhase.IMPORTING, "正在导入 GGUF…", it) }
                } }
                activate(model, name)
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { failure(e) }
        }
    }
    fun cancel() {
        if (!state.value.cancellable) return
        val previous = work; previous?.cancel()
        update(ModelPhase.DELETING, "正在取消…")
        work = scope.launch {
            try {
                previous?.join()
                val id = workerId
                withContext(Dispatchers.IO) {
                    if (id != null) workers.cancelWorkById(id).result.get(30, TimeUnit.SECONDS)
                    ModelTransferLocks.forDirectory(files.directory.path).withLock { clearTransfer(); files.removePartials() }
                }
                prefs.edit { remove("error"); remove("retryDownload") }; update(ModelPhase.IDLE, "已取消")
            } catch (e: Exception) { if (e is CancellationException) throw e; failure(e) }
        }
    }
    private fun persistTarget() {
        prefs.edit(commit = true) { putString("target", JSONObject().put("name", target.name).put("url", target.url).put("bytes", target.bytes)
            .put("sha256", target.sha256).put("license", target.license).put("repository", target.repository).put("revision", target.revision).toString()) }
    }
    fun retry() {
        if (state.value.canRetry && !state.value.busy) download(target)
    }
    fun select(hash: String) {
        if (state.value.busy || installed()?.sha256 == hash) return
        update(ModelPhase.SWITCHING, "正在切换模型…")
        work = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val saved = library.all().firstOrNull { it.model.sha256 == hash } ?: error("模型文件不存在，请重新下载")
                    settings.installModel(saved.model.path, saved.model.sha256, saved.name, saved.license, saved.repository, saved.revision)
                }
                prefs.edit { remove("error"); remove("retryDownload") }; update(ModelPhase.IDLE, "已切换模型")
            } catch (e: Exception) { if (e is CancellationException) throw e; failure(e) }
        }
    }
    fun delete(hash: String? = state.value.installed?.sha256) {
        if (state.value.busy || hash == null) return
        update(ModelPhase.DELETING, "正在删除模型…")
        work = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val saved = library.all().firstOrNull { it.model.sha256 == hash } ?: error("模型文件不存在")
                    files.remove(saved.model)
                    library.forget(hash)
                    if (settings.modelHash == hash) settings.clearModel()
                }
                prefs.edit { remove("error"); remove("retryDownload") }; update(ModelPhase.IDLE, "模型已删除")
            } catch (e: Exception) { if (e is CancellationException) throw e; failure(e) }
        }
    }
}
