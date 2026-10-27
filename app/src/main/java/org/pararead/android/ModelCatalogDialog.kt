package org.pararead.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.pararead.core.*

data class ModelCatalogState(val query: String = "", val loading: Boolean = false,
    val repositories: List<HfRepository> = emptyList(), val details: HfModelDetails? = null, val error: String = "")

class ModelCatalogViewModel : ViewModel() {
    private val catalog = HuggingFaceCatalog()
    private val mutable = MutableStateFlow(ModelCatalogState())
    val state = mutable.asStateFlow()
    private var work: Job? = null
    fun query(value: String) { mutable.value = state.value.copy(query = value.take(120)) }
    fun search() = request {
        val query = state.value.query.trim()
        if (HuggingFaceCatalog.validRepository(query)) state.value.copy(details = catalog.details(query))
        else state.value.copy(repositories = catalog.search(query), details = null)
    }
    fun details(id: String) = request { state.value.copy(details = catalog.details(id)) }
    fun back() { work?.cancel(); mutable.value = state.value.copy(details = null, loading = false, error = "") }
    fun stop() { work?.cancel(); mutable.value = state.value.copy(loading = false) }
    private fun request(block: suspend () -> ModelCatalogState) {
        work?.cancel()
        mutable.value = state.value.copy(loading = true, error = "")
        work = viewModelScope.launch {
            try { mutable.value = block().copy(loading = false, error = "") }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { mutable.value = state.value.copy(loading = false, error = e.message ?: "请求失败，请重试") }
        }
    }
}

@Composable
fun ModelCatalogDialog(state: ModelCatalogState, installedHash: String?, onQuery: (String) -> Unit,
    onSearch: () -> Unit, onDetails: (String) -> Unit, onBack: () -> Unit, onDownload: (ModelAsset) -> Unit, onClose: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    AppDialog("Hugging Face 模型", onClose, "发现更多适合你的本地模型", AppGlyph.Search) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val details = state.details
            if (details == null) {
                OutlinedTextField(state.query, onQuery, modifier = Modifier.fillMaxWidth(), label = { Text("模型名称或 org/repo") }, singleLine = true)
                Button(onClick = onSearch, enabled = !state.loading) { Text("搜索 GGUF") }
                Text("选择公开、单文件的文本 GGUF。当前适配 Qwen2.5、Qwen3、Qwen3.5 和 LFM2/2.5。", style = MaterialTheme.typography.bodySmall)
                if (state.repositories.isEmpty()) {
                    Text("快速查找", style = MaterialTheme.typography.titleSmall)
                    Text("轻量快读：Qwen3-0.6B Q4_K_M；官方备选 Q8_0。重要结论仍需对照原文。", style = MaterialTheme.typography.bodySmall)
                    listOf("unsloth/Qwen3-0.6B-GGUF", "Qwen/Qwen3-0.6B-GGUF", "ggml-org/Qwen3.5-0.8B-GGUF", "Qwen/Qwen2.5-0.5B-Instruct-GGUF").forEach { id ->
                        TextButton(onClick = { onDetails(id) }, enabled = !state.loading) { Text(id) }
                    }
                } else state.repositories.forEach { repo ->
                    TextButton(onClick = { onDetails(repo.id) }, enabled = !state.loading) { Text("${repo.id}\n下载量 ${repo.downloads}") }
                }
            } else {
                TextButton(onClick = onBack) { Text("返回搜索") }
                Text(details.repository, style = MaterialTheme.typography.titleSmall)
                Text("模型许可：${details.license}")
                TextButton(onClick = { uriHandler.openUri("https://huggingface.co/${details.repository}") }) { Text("查看模型卡与许可") }
                Text("下载成功后自动使用；其他已下载模型会保留，可在模型管理中切换或删除。较小模型可能遗漏译文或占位符；格式校验失败时不会写入译文。", style = MaterialTheme.typography.bodySmall)
                if (details.files.isEmpty()) Text("没有可下载的完整 GGUF 文件（需提供 LFS 大小与 SHA-256）。")
                details.files.forEach { file ->
                    HorizontalDivider()
                    Text(file.filename)
                    Text("${file.asset.bytes / 1048576} MiB · SHA-256 ${file.asset.sha256.take(12)}…", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { onDownload(file.asset) }, enabled = !state.loading && installedHash != file.asset.sha256) {
                        Text(if (installedHash == file.asset.sha256) "当前模型" else "下载并使用此模型")
                    }
                }
            }
            if (state.loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text("正在连接 Hugging Face…") }
            if (state.error.isNotEmpty()) Text(state.error, color = MaterialTheme.colorScheme.error)
        }
    }
}
