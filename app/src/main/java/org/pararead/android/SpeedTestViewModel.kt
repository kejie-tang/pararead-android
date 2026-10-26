package org.pararead.android

import android.app.Application
import android.os.Build
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import org.pararead.core.*
import org.pararead.llama.LlamaCppProvider
import org.pararead.llama.NativeBridge
import java.io.File

data class SavedSpeedReport(val result: SpeedTestResult, val metadata: JSONObject) {
    fun toJson(): JSONObject = result.toJson().put("environment", metadata)
    companion object {
        fun fromJson(raw: String): SavedSpeedReport {
            val j = JSONObject(raw)
            val env = j.getJSONObject("environment")
            require(env.getLong("startedAtEpochMs") > 0 && env.getInt("effectiveThreads") > 0)
            require(env.getString("modelSha256").matches(Regex("[a-f0-9]{64}")))
            env.getString("device")
            return SavedSpeedReport(SpeedTestResult.fromJson(j), env)
        }
    }
}

enum class SpeedPhase { READY, PREPARING, RUNNING, CANCELLING, COMPLETED, CANCELLED, ERROR }
data class SpeedTestState(
    val visible: Boolean = false, val phase: SpeedPhase = SpeedPhase.READY,
    val model: InstalledModel? = null, val requestedThreads: Int = 0, val effectiveThreads: Int = CpuThreads.resolve(0),
    val run: Int = 0, val message: String = "", val report: SavedSpeedReport? = null,
) {
    val busy get() = phase in setOf(SpeedPhase.PREPARING, SpeedPhase.RUNNING, SpeedPhase.CANCELLING)
}

/** The model and job survive activity recreation. Only a completed, validated run replaces the last report. */
class SpeedTestViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("speed-test", 0)
    private val mutable = MutableStateFlow(SpeedTestState(report = runCatching {
        SavedSpeedReport.fromJson(prefs.getString("last-report", null) ?: error("NO_REPORT"))
    }.getOrNull()))
    val state = mutable.asStateFlow()
    private var job: Job? = null
    private var pendingExport: String? = null
    fun prepareExport(): Boolean {
        pendingExport = state.value.report?.toJson()?.toString(2)
        return pendingExport != null
    }
    fun takeExport(): String? = pendingExport.also { pendingExport = null }

    fun open(model: InstalledModel, requestedThreads: Int, releaseReader: suspend () -> Unit) {
        if (state.value.visible || state.value.busy) return
        val effective = CpuThreads.resolve(requestedThreads)
        mutable.value = state.value.copy(visible = true, phase = SpeedPhase.PREPARING, model = model,
            requestedThreads = requestedThreads, effectiveThreads = effective, run = 0, message = "正在暂停阅读器并释放模型…")
        job = viewModelScope.launch {
            // Even cancellation must wait for the old reader's native model to leave memory.
            withContext(NonCancellable) { releaseReader() }
            mutable.value = state.value.copy(phase = if (isActive) SpeedPhase.READY else SpeedPhase.CANCELLED,
                message = if (isActive) "准备就绪" else "测速已取消")
        }
    }

    fun start() {
        if (!state.value.visible || state.value.busy) return
        val request = state.value
        val model = request.model ?: return
        mutable.value = request.copy(phase = SpeedPhase.RUNNING, run = 0, message = "正在加载本机模型…")
        job = viewModelScope.launch {
            var provider: LlamaCppProvider? = null
            var report: SavedSpeedReport? = null
            var failure: String? = null
            var cancelled = false
            try {
                require(File(model.path).isFile && File(model.path).length() == model.bytes) { "本机模型已更改，请重新打开测速" }
                val started = System.currentTimeMillis()
                provider = LlamaCppProvider(model.path, request.requestedThreads, singleResult = true)
                val result = SpeedTest.run(provider) { index ->
                    mutable.value = state.value.copy(run = index, message = if (index == 1) "首次加载 · 第 1 / 4 次" else "模型已加载 · 第 $index / 4 次")
                }
                val metadata = JSONObject().put("startedAtEpochMs", started).put("appVersion", BuildConfig.VERSION_NAME)
                    .put("device", "${Build.MANUFACTURER} ${Build.MODEL}").put("hardware", Build.HARDWARE)
                    .put("androidApi", Build.VERSION.SDK_INT).put("abis", Build.SUPPORTED_ABIS.joinToString())
                    .put("modelSha256", model.sha256).put("modelBytes", model.bytes)
                    .put("requestedThreads", request.requestedThreads).put("effectiveThreads", provider.threadCount)
                    .put("availableProcessors", CpuThreads.maximum()).put("engine", NativeBridge.version())
                    .put("singleResultGrammar", true)
                report = SavedSpeedReport(result, metadata)
            } catch (_: TimeoutCancellationException) {
                failure = "单次测试超过 120 秒，请减少线程数或检查模型后重试"
            } catch (_: CancellationException) {
                cancelled = true
            } catch (e: Exception) {
                failure = e.message ?: "测速失败"
            } finally {
                mutable.value = state.value.copy(message = "正在释放模型…")
                provider?.closeAndJoin()
            }
            cancelled = cancelled || !isActive
            if (report != null && !cancelled && failure == null) {
                withContext(NonCancellable) {
                    val saved = withContext(Dispatchers.IO) { prefs.edit().putString("last-report", report.toJson().toString()).commit() }
                    mutable.value = state.value.copy(phase = SpeedPhase.COMPLETED, report = report,
                        message = if (saved) "测速完成，结果已保存在本机" else "测速完成；本机保存失败，请导出 JSON")
                }
            } else {
                mutable.value = state.value.copy(phase = if (cancelled) SpeedPhase.CANCELLED else SpeedPhase.ERROR,
                    message = if (cancelled) "测速已取消；未覆盖上次完整结果" else "测速失败：$failure")
            }
        }
    }

    fun cancel() {
        if (!state.value.busy || state.value.phase == SpeedPhase.CANCELLING) return
        mutable.value = state.value.copy(phase = SpeedPhase.CANCELLING, message = "正在取消并释放模型…")
        job?.cancel()
    }
    fun dismiss() { if (state.value.busy) cancel() else mutable.value = state.value.copy(visible = false) }
}
