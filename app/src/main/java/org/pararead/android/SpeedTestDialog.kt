package org.pararead.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.pararead.core.SpeedTest
import java.text.DateFormat
import java.util.Date
import java.util.Locale

private fun ms(value: Double) = String.format(Locale.ROOT, "%.2f s", value / 1000)
private fun tps(value: Double) = String.format(Locale.ROOT, "%.1f token/s", value)
private fun share(nanos: Long, total: Double) = String.format(Locale.ROOT, "%.1f%%", nanos * 100 / total)

@Composable
fun SpeedTestDialog(state: SpeedTestState, onStart: () -> Unit, onCancel: () -> Unit, onClose: () -> Unit, onExport: () -> Unit) {
    val text = LocalContext.current
    var elapsed by remember { mutableLongStateOf(0) }
    var details by remember { mutableStateOf(false) }
    LaunchedEffect(state.phase, state.run) {
        elapsed = 0
        if (state.phase == SpeedPhase.RUNNING) {
            val start = android.os.SystemClock.elapsedRealtime()
            while (true) { delay(200); elapsed = android.os.SystemClock.elapsedRealtime() - start }
        }
    }
    AppDialog(text.getString(R.string.speed_title), onClose, text.getString(R.string.speed_subtitle), AppGlyph.Sliders,
        closeLabel = text.getString(R.string.close_speed), closeEnabled = !state.busy, footer = {
            if (state.busy) TextButton(enabled = state.phase != SpeedPhase.CANCELLING, onClick = onCancel) { Text(text.getString(R.string.cancel_speed)) }
            else Button(onClick = onStart) { Text(if (state.phase == SpeedPhase.READY) text.getString(R.string.start_speed) else text.getString(R.string.run_again)) }
        }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SettingsSection(text.getString(R.string.method), AppGlyph.Sliders) {
                Text(text.getString(R.string.speed_method), style = MaterialTheme.typography.bodyMedium)
                Text(text.getString(R.string.speed_threads, state.effectiveThreads, if (state.requestedThreads == 0) text.getString(R.string.automatic_suffix) else "", state.model?.sha256?.take(12)),
                    style = MaterialTheme.typography.bodySmall)
                Text(displayStatus(state.backendStatus?.description ?: "请求后端：${state.backend.label}"), style = MaterialTheme.typography.bodySmall)
                Text(text.getString(R.string.speed_direction, SpeedTest.LONG_SOURCE.split(' ').size), style = MaterialTheme.typography.bodySmall)
                Text(text.getString(R.string.speed_conditions), style = MaterialTheme.typography.bodySmall)
                if (state.busy) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(displayStatus(state.message) + if (state.phase == SpeedPhase.RUNNING) text.getString(R.string.elapsed_seconds, elapsed / 1000) else "")
                } else Text(displayStatus(state.message), style = MaterialTheme.typography.titleSmall, color = if (state.phase == SpeedPhase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary)
            }
            state.report?.let { report ->
                val result = report.result
                val env = report.metadata
                Text(text.getString(R.string.latest_result), style = MaterialTheme.typography.titleMedium)
                Text(text.getString(R.string.source_words, result.source.split(' ').size), style = MaterialTheme.typography.bodySmall)
                Text(DateFormat.getDateTimeInstance().format(Date(env.getLong("startedAtEpochMs"))), style = MaterialTheme.typography.bodySmall)
                Text(text.getString(R.string.speed_device, env.getString("device"), env.getInt("effectiveThreads"), env.getString("modelSha256").take(12)),
                    style = MaterialTheme.typography.bodySmall)
                Text(text.getString(R.string.backend_pair, env.optString("requestedBackend", "CPU"), env.optString("actualBackend", "CPU")) +
                    if (env.optString("actualBackend") == "VULKAN") " · ${env.optString("gpuDevice")}" else "", style = MaterialTheme.typography.bodySmall)
                if (env.optString("fallbackReason").isNotEmpty()) Text(text.getString(R.string.fallback_format, env.getString("fallbackReason")), style = MaterialTheme.typography.bodySmall)
                SpeedMetric(text.getString(R.string.translation_speed), tps(result.warmMedian { it.metrics.tokensPerSecond }), text.getString(R.string.generation_hint))
                env.optJSONArray("engineSamples")?.let { rows ->
                    val rates = (0 until rows.length()).map { rows.getJSONObject(it).getDouble("decodeTokensPerSecond") }.sorted()
                    if (rates.size == 3) SpeedMetric(text.getString(R.string.engine_decode), tps(rates[1]), text.getString(R.string.engine_hint))
                    val prefill = (0 until rows.length()).map { rows.getJSONObject(it).getDouble("prefillTokensPerSecond") }.sorted()
                    if (prefill.size == 3) Text(text.getString(R.string.benchmark_median, tps(prefill[1]), tps(rates[1])), style = MaterialTheme.typography.bodySmall)
                }
                TextButton(onClick = { details = !details }) { Text(if (details) text.getString(R.string.hide_details) else text.getString(R.string.show_details)) }
                if (details) SettingsSection(text.getString(R.string.details), AppGlyph.File) {
                    if (env.has("cpuPlugin")) Text(text.getString(R.string.cpu_kernel_format, env.getString("cpuPlugin")), style = MaterialTheme.typography.bodySmall)
                    if (env.optString("cpuThreadpool").startsWith("persistent")) Text(text.getString(R.string.cpu_pool), style = MaterialTheme.typography.bodySmall)
                    Text(text.getString(R.string.cold_result, ms(result.first.firstResponseMs), ms(result.first.totalMs)), style = MaterialTheme.typography.bodyMedium)
                    Text(text.getString(R.string.warm_result, ms(result.warmMedian { it.firstResponseMs }), ms(result.warmMedian { it.totalMs })), style = MaterialTheme.typography.bodyMedium)
                    Text(text.getString(R.string.speed_formula), style = MaterialTheme.typography.bodySmall)
                    val generation = result.samples.drop(1).sumOf { it.metrics.generationNanos }.toDouble()
                    if (generation > 0 && result.first.metrics.decodeNanos > 0) {
                        Text(text.getString(R.string.overhead_format, share(result.samples.drop(1).sumOf { it.metrics.decodeNanos }, generation), share(result.samples.drop(1).sumOf { it.metrics.samplingNanos }, generation), share(result.samples.drop(1).sumOf { it.metrics.callbackNanos }, generation)), style = MaterialTheme.typography.bodySmall)
                    }
                    result.samples.forEachIndexed { index, sample ->
                        Text(text.getString(R.string.sample_format, index + 1, ms(sample.totalMs), sample.metrics.generatedTokens, tps(sample.metrics.tokensPerSecond)), style = MaterialTheme.typography.bodySmall)
                    }
                    Text(text.getString(R.string.translation_format, result.first.translation), style = MaterialTheme.typography.bodyMedium)
                }
                TextButton(enabled = !state.busy, onClick = onExport) { Text(text.getString(R.string.export_speed)) }
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
