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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.pararead.core.*
import java.io.File
import java.util.UUID

enum class ModelPhase { IDLE, DOWNLOADING, PAUSED, VERIFYING, IMPORTING, DELETING, ERROR }
data class ModelState(val installed: InstalledModel? = null, val phase: ModelPhase = ModelPhase.IDLE,
    val completed: Long = 0, val total: Long = DefaultModel.asset.bytes, val message: String = "") {
    val busy get() = phase !in setOf(ModelPhase.IDLE, ModelPhase.ERROR)
    val cancellable get() = phase in setOf(ModelPhase.DOWNLOADING, ModelPhase.PAUSED, ModelPhase.VERIFYING, ModelPhase.IMPORTING)
}

class ModelViewModel(app: Application) : AndroidViewModel(app) {
    val manager = ModelManager(app, viewModelScope)
}

/** DownloadManager owns network work across Activity/process recreation; only verified private GGUFs are activated. */
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
    private val downloadDirectory = requireNotNull(app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS))
    private val staging get() = File(downloadDirectory, prefs.getString("file", "qwen3-download.part")!!)
    private val mutable = MutableStateFlow(ModelState(installed(),
        phase = if (prefs.getString("error", "").isNullOrEmpty()) ModelPhase.IDLE else ModelPhase.ERROR,
        message = prefs.getString("error", "") ?: ""))
    val state = mutable.asStateFlow()
    private var work: Job? = null
    private var downloadId: Long
        get() = prefs.getLong("id", 0)
        set(value) { prefs.edit(commit = true) { putLong("id", value) } }

    init {
        if (downloadId > 0) monitor()
        else if (state.value.installed?.sha256 == target.sha256 && state.value.message == "下载任务已移除，请重试") {
            prefs.edit { remove("error") }; update(ModelPhase.IDLE, "模型已就绪，思考模式已关闭")
        }
    }
    private fun installed() = settings.modelPath.takeIf { it.isNotEmpty() }?.let(::File)?.takeIf { it.isFile }
        ?.let { InstalledModel(it.absolutePath, settings.modelHash, it.length()) }
    private fun update(phase: ModelPhase, message: String, completed: Long = 0) {
        mutable.value = ModelState(installed(), phase, completed, target.bytes, message)
    }
    private fun clearTransfer() {
        val temp = staging
        if (downloadId > 0) downloads.remove(downloadId)
        prefs.edit(commit = true) { putLong("id", 0); remove("file") }; temp.delete()
    }
    private fun failure(e: Exception) {
        val message = e.message ?: "模型操作失败，请重试"
        prefs.edit { putString("error", message) }; update(ModelPhase.ERROR, message)
    }
    fun download(selected: ModelAsset = asset) {
        if (state.value.busy || state.value.installed?.sha256 == selected.sha256) return
        target = selected
        update(ModelPhase.DOWNLOADING, "正在准备下载…")
        try {
            require(target.bytes > 24 && target.bytes < Long.MAX_VALUE / 3 && target.sha256.matches(Regex("[a-f0-9]{64}"))) { "模型大小或 SHA-256 无效" }
            require(target.url.startsWith("https://") || (BuildConfig.DEBUG && target.url.toUri().host in setOf("127.0.0.1", "localhost"))) { "模型下载地址必须使用 HTTPS" }
            require(StatFs(files.directory.path).availableBytes >= target.bytes * 2 + 67108864L && StatFs(downloadDirectory.path).availableBytes >= target.bytes) { "空间不足，下载并校验约需 ${target.bytes * 2 / 1048576 + 64} MiB 可用空间" }
            clearTransfer(); prefs.edit { remove("error") }
            // DownloadManager can finish cleaning a cancelled job after a replacement has started.
            prefs.edit(commit = true) {
                putString("file", "model-${UUID.randomUUID()}.part")
                putString("target", JSONObject().put("name", target.name).put("url", target.url).put("bytes", target.bytes)
                    .put("sha256", target.sha256).put("license", target.license).put("repository", target.repository).put("revision", target.revision).toString())
            }
            val request = DownloadManager.Request(target.url.toUri()).setTitle("ParaRead · ${target.name}")
                .setDescription("下载后校验 SHA-256 · 离线翻译")
                .setMimeType("application/octet-stream").setDestinationUri(Uri.fromFile(staging))
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            downloadId = downloads.enqueue(request)
            monitor()
        } catch (e: Exception) { failure(e) }
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
            catch (e: Exception) { runCatching { clearTransfer() }; failure(e) }
        }
    }
    private suspend fun activate(model: InstalledModel, name: String, source: ModelAsset? = null) {
        // Publication and preferences must finish together even if monitoring is then cancelled.
        withContext(NonCancellable + Dispatchers.IO) {
            settings.installModel(model.path, model.sha256, name, source?.license ?: "", source?.repository ?: "", source?.revision ?: "")
            files.removeExcept(model.path)
            // Do not announce IDLE until the persistent transfer record and second weight copy are gone.
            clearTransfer()
        }
        prefs.edit { remove("error") }; update(ModelPhase.IDLE, "模型已就绪，思考模式已关闭")
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
                previous?.join(); withContext(Dispatchers.IO) { clearTransfer(); files.removeExcept(settings.modelPath) }
                update(ModelPhase.IDLE, "已取消")
            } catch (e: Exception) { if (e is CancellationException) throw e; failure(e) }
        }
    }
    fun delete() {
        if (state.value.busy) return
        update(ModelPhase.DELETING, "正在删除模型…")
        work = scope.launch {
            try {
                withContext(Dispatchers.IO) { clearTransfer(); settings.clearModel(); files.removeExcept() }
                prefs.edit { remove("error") }; update(ModelPhase.IDLE, "模型已删除")
            } catch (e: Exception) { if (e is CancellationException) throw e; failure(e) }
        }
    }
}
