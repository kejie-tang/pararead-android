package org.pararead.android

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
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
    val text = LocalContext.current
    val uriHandler = LocalUriHandler.current
    AppDialog(text.getString(R.string.hf_models), onClose, text.getString(R.string.hf_subtitle), AppGlyph.Search) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val details = state.details
            if (details == null) {
                OutlinedTextField(state.query, onQuery, modifier = Modifier.fillMaxWidth(), label = { Text(text.getString(R.string.model_query)) }, singleLine = true)
                Button(onClick = onSearch, enabled = !state.loading) { Text(text.getString(R.string.search_gguf)) }
                Text(text.getString(R.string.hf_supported), style = MaterialTheme.typography.bodySmall)
                if (state.repositories.isEmpty()) {
                    Text(text.getString(R.string.quick_search), style = MaterialTheme.typography.titleSmall)
                    Text(text.getString(R.string.hf_fast_hint), style = MaterialTheme.typography.bodySmall)
                    listOf("unsloth/Qwen3-0.6B-GGUF", "Qwen/Qwen3-0.6B-GGUF", "ggml-org/Qwen3.5-0.8B-GGUF", "Qwen/Qwen2.5-0.5B-Instruct-GGUF").forEach { id ->
                        TextButton(onClick = { onDetails(id) }, enabled = !state.loading) { Text(id) }
                    }
                } else state.repositories.forEach { repo ->
                    TextButton(onClick = { onDetails(repo.id) }, enabled = !state.loading) { Text(text.getString(R.string.repo_downloads, repo.id, repo.downloads)) }
                }
            } else {
                TextButton(onClick = onBack) { Text(text.getString(R.string.back_search)) }
                Text(details.repository, style = MaterialTheme.typography.titleSmall)
                Text(text.getString(R.string.model_license_format, details.license))
                TextButton(onClick = { uriHandler.openUri("https://huggingface.co/${details.repository}") }) { Text(text.getString(R.string.model_card_license)) }
                Text(text.getString(R.string.hf_download_hint), style = MaterialTheme.typography.bodySmall)
                if (details.files.isEmpty()) Text(text.getString(R.string.no_gguf_files))
                details.files.forEach { file ->
                    HorizontalDivider()
                    Text(file.filename)
                    Text("${file.asset.bytes / 1048576} MiB · SHA-256 ${file.asset.sha256.take(12)}…", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = { onDownload(file.asset) }, enabled = !state.loading && installedHash != file.asset.sha256) {
                        Text(if (installedHash == file.asset.sha256) text.getString(R.string.current_model) else text.getString(R.string.download_this_model))
                    }
                }
            }
            if (state.loading) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(text.getString(R.string.hf_connecting)) }
            if (state.error.isNotEmpty()) Text(displayStatus(state.error), color = MaterialTheme.colorScheme.error)
        }
    }
}
