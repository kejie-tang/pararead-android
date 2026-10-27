package org.pararead.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import org.pararead.core.*
import java.util.Locale

internal fun modelSize(bytes: Long): String = if (bytes >= 1000000000L)
    String.format(Locale.ROOT, "%.2f GB", bytes / 1e9) else "${(bytes / 1e6).toLong() + if (bytes % 1000000 >= 500000) 1 else 0} MB"
internal fun SavedModel.displayName() = RecommendedModels.name(model.sha256, name)
internal fun ModelState.activeName(): String = installed?.let { active ->
    RecommendedModels.name(active.sha256, models.firstOrNull { it.model.sha256 == active.sha256 }?.name ?: "本机 GGUF")
} ?: "尚未选择模型"

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ModelLibraryDialog(state: ModelState, actionsEnabled: Boolean = true, onDownload: (ModelAsset) -> Unit,
    onUse: (String) -> Unit, onDelete: (String) -> Unit, onRetry: () -> Unit, onCancel: () -> Unit,
    onImport: () -> Unit, onMore: () -> Unit, onClose: () -> Unit) {
    var deleting by remember { mutableStateOf<SavedModel?>(null) }
    val enabled = actionsEnabled && !state.busy
    AlertDialog(onDismissRequest = onClose, modifier = Modifier.widthIn(max = 680.dp).fillMaxWidth(.94f),
        properties = DialogProperties(usePlatformDefaultWidth = false), title = { Text("模型管理") }, text = {
            Column(Modifier.fillMaxWidth().heightIn(max = 560.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("当前使用：${state.activeName()}", style = MaterialTheme.typography.titleMedium)
                Text("两个模型可同时保留，切换无需重新下载。下载完成后自动使用新模型。", style = MaterialTheme.typography.bodySmall)
                if (state.busy || state.phase == ModelPhase.ERROR) {
                    state.target?.let { Text("${RecommendedModels.name(it.sha256, it.name)} · ${modelSize(it.bytes)}") }
                    ModelTransferProgress(state)
                    if (state.cancellable) TextButton(onClick = onCancel) { Text("取消下载或导入") }
                    if (state.canRetry) Button(onClick = onRetry, enabled = actionsEnabled) { Text("重试此模型下载") }
                } else if (state.message.isNotEmpty()) Text(state.message, style = MaterialTheme.typography.bodySmall)
                for (asset in RecommendedModels.all) {
                    val saved = state.models.firstOrNull { it.model.sha256 == asset.sha256 }
                    val fast = asset.sha256 == RecommendedModels.fast.sha256
                    ModelCard(RecommendedModels.name(asset.sha256, asset.name),
                        if (fast) "轻量快读 · 速度与体积优先" else "质量优先 · 翻译质量优先",
                        "Q4_K_M · ${modelSize(asset.bytes)}", asset.sha256, saved,
                        state.installed?.sha256 == asset.sha256, enabled,
                        onDownload = { onDownload(asset) }, onUse = { onUse(asset.sha256) }, onDelete = { deleting = saved },
                        license = asset.license, sourceUrl = if (fast) "https://huggingface.co/unsloth/Qwen3-0.6B-GGUF" else "https://ollama.com/library/qwen3:1.7b",
                        spaceHint = if (saved == null) "下载时需额外约 ${modelSize(asset.bytes * 2 + 67108864L)} 可用空间" else "")
                }
                val other = state.models.filter { saved -> RecommendedModels.all.none { it.sha256 == saved.model.sha256 } }
                if (other.isNotEmpty()) Text("其他已下载模型", style = MaterialTheme.typography.titleSmall)
                for (saved in other) ModelCard(saved.displayName(), "已保存到本机", modelSize(saved.model.bytes), saved.model.sha256,
                    saved, state.installed?.sha256 == saved.model.sha256, enabled, {},
                    { onUse(saved.model.sha256) }, { deleting = saved })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onImport, enabled = enabled) { Text("导入 GGUF") }
                    TextButton(onClick = onMore, enabled = enabled) { Text("更多模型（Hugging Face）") }
                }
            }
        }, confirmButton = { TextButton(onClick = onClose) { Text("关闭") } })
    deleting?.let { saved ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("删除 ${saved.displayName()}？") },
            text = { Text("将释放 ${modelSize(saved.model.bytes)} 空间。PDF、译文和阅读进度会保留。" +
                if (state.installed?.sha256 == saved.model.sha256) "这是当前模型，删除后需选择其他模型。" else "") },
            confirmButton = { TextButton(enabled = enabled, onClick = { onDelete(saved.model.sha256); deleting = null }) { Text("确认删除") } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelCard(name: String, description: String, size: String, hash: String, saved: SavedModel?,
    current: Boolean, enabled: Boolean, onDownload: () -> Unit, onUse: () -> Unit, onDelete: () -> Unit,
    license: String = saved?.license ?: "", sourceUrl: String? = null, spaceHint: String = "") {
    val uriHandler = LocalUriHandler.current
    OutlinedCard(Modifier.fillMaxWidth().testTag("model-$hash")) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodyMedium)
            Text(size, style = MaterialTheme.typography.bodySmall)
            if (spaceHint.isNotEmpty()) Text(spaceHint, style = MaterialTheme.typography.bodySmall)
            if (license.isNotEmpty()) Text("许可：$license", style = MaterialTheme.typography.bodySmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = if (saved == null) onDownload else onUse, enabled = enabled && !current) {
                    Text(when { current -> "当前使用"; saved != null -> "使用"; else -> "下载并使用" })
                }
                if (saved != null) TextButton(onClick = onDelete, enabled = enabled) { Text("删除") }
                if (sourceUrl != null) TextButton(onClick = { uriHandler.openUri(sourceUrl) }) { Text("来源与许可") }
            }
        }
    }
}

@Composable
internal fun ModelTransferProgress(state: ModelState) {
    if (state.message.isNotEmpty()) Text(state.message,
        color = if (state.phase == ModelPhase.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    if (state.busy && state.phase !in setOf(ModelPhase.DELETING, ModelPhase.SWITCHING)) {
        if (state.total > 0 && state.phase != ModelPhase.IMPORTING) {
            LinearProgressIndicator(progress = { (state.completed.toDouble() / state.total).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
            Text("${modelSize(state.completed)} / ${modelSize(state.total)} · ${(state.completed * 100 / state.total).coerceIn(0, 100)}%", style = MaterialTheme.typography.bodySmall)
        } else {
            LinearProgressIndicator(Modifier.fillMaxWidth()); Text(modelSize(state.completed), style = MaterialTheme.typography.bodySmall)
        }
    }
}
