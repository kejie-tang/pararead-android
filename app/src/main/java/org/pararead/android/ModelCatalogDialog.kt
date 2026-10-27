package org.pararead.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
    AlertDialog(onDismissRequest = onClose, title = { Text("Hugging Face 模型") }, text = {
        Column(Modifier.fillMaxWidth().heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val details = state.details
            if (details == null) {
                OutlinedTextField(state.query, onQuery, label = { Text("模型名称或 org/repo") }, singleLine = true)
                Button(onClick = onSearch, enabled = !state.loading) { Text("搜索 GGUF") }
                Text("选择公开、单文件的文本 GGUF。当前适配 Qwen3、Qwen3.5 和 LFM2.5；其他架构暂不支持。", style = MaterialTheme.typography.bodySmall)
                if (state.repositories.isEmpty()) {
                    Text("快速查找", style = MaterialTheme.typography.titleSmall)
                    listOf("LiquidAI/LFM2.5-1.2B-Instruct-GGUF", "LiquidAI/LFM2.5-350M-GGUF", "ggml-org/Qwen3.5-0.8B-GGUF").forEach { id ->
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
                Text("下载成功后替换当前本机模型。较小模型可能遗漏译文或占位符；格式校验失败时不会写入译文。", style = MaterialTheme.typography.bodySmall)
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
    }, confirmButton = { TextButton(onClick = onClose) { Text("关闭") } })
}
