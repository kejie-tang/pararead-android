package org.pararead.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.pararead.core.SpeedTest
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private fun ms(value: Double) = String.format(Locale.ROOT, "%.2f 秒", value / 1000)
private fun tps(value: Double) = String.format(Locale.ROOT, "%.1f token/s", value)
private fun share(nanos: Long, total: Double) = String.format(Locale.ROOT, "%.1f%%", nanos * 100 / total)

@Composable
fun SpeedTestDialog(state: SpeedTestState, onStart: () -> Unit, onCancel: () -> Unit, onClose: () -> Unit, onExport: () -> Unit) {
    var elapsed by remember { mutableLongStateOf(0) }
    var details by remember { mutableStateOf(false) }
    LaunchedEffect(state.phase, state.run) {
        elapsed = 0
        if (state.phase == SpeedPhase.RUNNING) {
            val start = android.os.SystemClock.elapsedRealtime()
            while (true) { delay(200); elapsed = android.os.SystemClock.elapsedRealtime() - start }
        }
    }
    AppDialog("离线模型速度测试", onClose, "了解当前设备上的真实表现", AppGlyph.Sliders,
        closeLabel = "关闭测速", closeEnabled = !state.busy, footer = {
            if (state.busy) TextButton(enabled = state.phase != SpeedPhase.CANCELLING, onClick = onCancel) { Text("取消测速") }
            else Button(onClick = onStart) { Text(if (state.phase == SpeedPhase.READY) "开始测速" else "重新测速") }
        }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsSection("测试方式", AppGlyph.Sliders) {
                Text("翻译固定英文长段：首次加载 1 次 + 热模型 3 次；随后运行 3 次纯引擎 pp512 / tg128 基准。", style = MaterialTheme.typography.bodyMedium)
                Text("CPU ${state.effectiveThreads} 线程${if (state.requestedThreads == 0) "（自动）" else ""} · 模型 ${state.model?.sha256?.take(12)}",
                    style = MaterialTheme.typography.bodySmall)
                Text(state.backendStatus?.description ?: "请求后端：${state.backend.label}", style = MaterialTheme.typography.bodySmall)
                Text("英 → 简体中文 · ${SpeedTest.LONG_SOURCE.split(' ').size} 个英文单词 · 不使用译文缓存", style = MaterialTheme.typography.bodySmall)
                Text("测试期间阅读器暂停。首次响应包含本次模型加载和提示处理；系统文件缓存可能仍然存在。每次最多 120 秒。", style = MaterialTheme.typography.bodySmall)
                if (state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(state.message + if (state.phase == SpeedPhase.RUNNING) " · ${elapsed / 1000} 秒" else "")
                } else Text(state.message, style = MaterialTheme.typography.titleSmall, color = if (state.phase == SpeedPhase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary)
            }
            state.report?.let { report ->
                val result = report.result
                val env = report.metadata
                Text("最近一次完整结果", style = MaterialTheme.typography.titleMedium)
                Text("本次结果原文：${result.source.split(' ').size} 个英文单词", style = MaterialTheme.typography.bodySmall)
                Text(DateFormat.getDateTimeInstance().format(Date(env.getLong("startedAtEpochMs"))), style = MaterialTheme.typography.bodySmall)
                Text("${env.getString("device")} · CPU ${env.getInt("effectiveThreads")} 线程 · ${env.getString("modelSha256").take(12)}",
                    style = MaterialTheme.typography.bodySmall)
                Text("请求：${env.optString("requestedBackend", "CPU")} · 实际：${env.optString("actualBackend", "CPU")}" +
                    if (env.optString("actualBackend") == "VULKAN") " · ${env.optString("gpuDevice")}" else "", style = MaterialTheme.typography.bodySmall)
                if (env.optString("fallbackReason").isNotEmpty()) Text("回退原因：${env.getString("fallbackReason")}", style = MaterialTheme.typography.bodySmall)
                SpeedMetric("翻译生成速度：", tps(result.warmMedian { it.metrics.tokensPerSecond }), "含 JSON 格式 token，不含模型加载和提示处理时间。")
                env.optJSONArray("engineSamples")?.let { rows ->
                    val rates = (0 until rows.length()).map { rows.getJSONObject(it).getDouble("decodeTokensPerSecond") }.sorted()
                    if (rates.size == 3) SpeedMetric("纯引擎解码 · tg128", tps(rates[1]), "不含 JSON 约束、采样及逐 token 界面回调；请与同设置的引擎基准比较。")
                    val prefill = (0 until rows.length()).map { rows.getJSONObject(it).getDouble("prefillTokensPerSecond") }.sorted()
                    if (prefill.size == 3) Text("pp512：${tps(prefill[1])} · tg128：${tps(rates[1])} · 三次中位数", style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { details = !details }) { Text(if (details) "收起详细数据与译文" else "展开详细数据与译文") }
                if (details) SettingsSection("详细数据与译文", AppGlyph.File) {
                    if (env.has("cpuPlugin")) Text("CPU 内核：${env.getString("cpuPlugin")}", style = MaterialTheme.typography.bodySmall)
                    if (env.optString("cpuThreadpool").startsWith("persistent")) Text("CPU 线程池：复用", style = MaterialTheme.typography.bodySmall)
                    Text("首次加载：响应 ${ms(result.first.firstResponseMs)} · 完成 ${ms(result.first.totalMs)}", style = MaterialTheme.typography.bodyMedium)
                    Text("热模型中位数：响应 ${ms(result.warmMedian { it.firstResponseMs })} · 完成 ${ms(result.warmMedian { it.totalMs })}", style = MaterialTheme.typography.bodyMedium)
                    Text("生成速度按实际生成 token 数 ÷ 生成阶段耗时计算。", style = MaterialTheme.typography.bodySmall)
                    val generation = result.samples.drop(1).sumOf { it.metrics.generationNanos }.toDouble()
                    if (generation > 0 && result.first.metrics.decodeNanos > 0) {
                        Text("热模型耗时占比：解码 ${share(result.samples.drop(1).sumOf { it.metrics.decodeNanos }, generation)} · 采样/格式约束 ${share(result.samples.drop(1).sumOf { it.metrics.samplingNanos }, generation)} · 回调 ${share(result.samples.drop(1).sumOf { it.metrics.callbackNanos }, generation)}", style = MaterialTheme.typography.bodySmall)
                    }
                    result.samples.forEachIndexed { index, sample ->
                        Text("第 ${index + 1} 次：${ms(sample.totalMs)} · ${sample.metrics.generatedTokens} tokens · ${tps(sample.metrics.tokensPerSecond)}", style = MaterialTheme.typography.bodySmall)
                    }
                    Text("译文：${result.first.translation}", style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(enabled = !state.busy, onClick = onExport) { Text("导出测速结果 JSON") }
            }
        }
    }
}

@Composable
private fun SpeedMetric(label: String, value: String, description: String) {
    Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            Text(value, style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.secondary)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
