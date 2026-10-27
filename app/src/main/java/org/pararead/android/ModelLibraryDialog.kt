package org.pararead.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
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
    AppDialog("模型管理", onClose, "选择适合你的阅读节奏", AppGlyph.Model) {
        Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.tertiaryContainer) {
            Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppIcon(if (state.installed == null) AppGlyph.Model else AppGlyph.Check, Modifier.size(18.dp), MaterialTheme.colorScheme.onTertiaryContainer)
                Text("当前使用：${state.activeName()}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
        Text("模型可以同时保留，随时离线切换。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                if (fast) "轻量快读 · 适合快速浏览" else "质量优先 · 适合日常论文阅读",
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
    val colors = MaterialTheme.colorScheme
    Surface(Modifier.fillMaxWidth().testTag("model-$hash"), shape = RoundedCornerShape(20.dp), color = colors.surface,
        border = BorderStroke(1.dp, if (current) colors.secondary.copy(alpha = .4f) else colors.outlineVariant)) {
        Column(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    IconTile(AppGlyph.Model, color = if (current) colors.secondary else colors.onSurfaceVariant)
                    Column(Modifier.weight(1f)) {
                        Text(name, style = MaterialTheme.typography.titleMedium)
                        Text(description, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    }
                }
                Text(size, style = MaterialTheme.typography.titleSmall, color = colors.onSurfaceVariant)
                if (spaceHint.isNotEmpty()) Text(spaceHint, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                if (license.isNotEmpty()) Text("许可：$license", style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            HorizontalDivider(color = colors.outlineVariant)
            FlowRow(Modifier.padding(12.dp, 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = if (saved == null) onDownload else onUse, enabled = enabled && !current,
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.secondary, disabledContentColor = if (current) colors.tertiary else colors.onSurfaceVariant.copy(alpha=.4f))) {
                    AppIcon(if (current) AppGlyph.Check else if (saved == null) AppGlyph.Download else AppGlyph.Arrow, Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp)); Text(when { current -> "当前使用"; saved != null -> "使用"; else -> "下载并使用" })
                }
                if (saved != null) TextButton(onClick = onDelete, enabled = enabled, colors = ButtonDefaults.textButtonColors(contentColor = colors.error)) {
                    AppIcon(AppGlyph.Trash, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("删除")
                }
                if (sourceUrl != null) TextButton(onClick = { uriHandler.openUri(sourceUrl) }, colors = ButtonDefaults.textButtonColors(contentColor = colors.onSurfaceVariant)) {
                    Text("来源与许可"); Spacer(Modifier.width(5.dp)); AppIcon(AppGlyph.External, Modifier.size(15.dp))
                }
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
