package org.pararead.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Debug
import androidx.activity.ComponentActivity
import androidx.activity.viewModels
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.core.view.WindowCompat
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.pararead.core.*
import org.pararead.llama.LlamaCppProvider
import org.pararead.llama.InferenceBackend
import org.pararead.llama.BackendStatus

class MainActivity : ComponentActivity() {
    companion object { private val backendRecoveryChecked = java.util.concurrent.atomic.AtomicBoolean() }
    private val activityStarted = android.os.SystemClock.elapsedRealtime()
    private lateinit var db: ReaderDatabase
    private lateinit var repository: DocumentRepository
    private lateinit var settings: Settings
    private val modelViewModel: ModelViewModel by viewModels()
    private val speedTestViewModel: SpeedTestViewModel by viewModels()
    private val modelCatalogViewModel: ModelCatalogViewModel by viewModels()
    private var host: ReaderHost? = null
    private var readerScope: CoroutineScope? = null
    private var readerRelease: Deferred<Unit>? = null
    private var selected by mutableStateOf<DocumentRecord?>(null)
    private var readerVersion by mutableIntStateOf(0)
    private var documents by mutableStateOf<List<DocumentRecord>>(emptyList())
    private var status by mutableStateOf("导入 PDF，或打开示例文档")
    private var localBackendStatus by mutableStateOf<BackendStatus?>(null)
    private var showSettings by mutableStateOf(false)
    private var uiLanguage by mutableStateOf("system")
    private var showModelLibrary by mutableStateOf(false)
    private var showModelCatalog by mutableStateOf(false)
    private var importTask: Job? = null
    private var importing by mutableStateOf(false)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        db = ReaderDatabase.open(this); repository = DocumentRepository(this, db.reader()); settings = Settings(this)
        uiLanguage = settings.uiLanguage
        if (backendRecoveryChecked.compareAndSet(false, true)) settings.recoverInterruptedVulkan()
        lifecycleScope.launch {
            documents = withContext(Dispatchers.IO) { db.reader().documents() }
            val restored = savedInstanceState?.getString("documentId")
            selected = documents.firstOrNull { it.id == restored }
            if (BuildConfig.DEBUG && intent.getBooleanExtra("sample", false)) {
                if (intent.getBooleanExtra("mock", false)) settings.provider = "mock"
                selected = repository.sample()
            }
            if (BuildConfig.DEBUG && intent.hasExtra("benchmarkFile")) {
                settings.provider = "mock"
                val fixture = repository.benchmark(intent.getStringExtra("benchmarkFile")!!)
                withContext(Dispatchers.IO) { db.reader().reading(ReadingRecord(fixture.id, "{}")) }
                selected = fixture
            }
        }
        setContent { AppLanguage(uiLanguage) { ParaReadTheme {
            val dark = isSystemInDarkTheme()
            SideEffect { WindowCompat.getInsetsController(window, window.decorView).apply {
                isAppearanceLightStatusBars = !dark; isAppearanceLightNavigationBars = !dark
            } }
            Surface(color = MaterialTheme.colorScheme.background) { App() }
        } } }
    }
    override fun onSaveInstanceState(outState: Bundle) { outState.putString("documentId", selected?.id); super.onSaveInstanceState(outState) }
    private fun reopen() {
        val previous = host; val pending = readerRelease
        previous?.close(); readerScope?.cancel(); host = null
        readerRelease = lifecycleScope.async { pending?.await(); previous?.awaitProviderClosed(); Unit }
        readerVersion++
    }
    private fun runImport(block: suspend () -> Unit) {
        importTask?.cancel()
        importTask = lifecycleScope.launch {
            importing = true
            try { block() } catch (_: CancellationException) { status = "导入已取消" }
            catch (e: Exception) { status = "导入失败：${e.message}" }
            finally { importing = false }
        }
    }
    @OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
    @Composable private fun App() {
        val modelState by modelViewModel.manager.state.collectAsState()
        val speedState by speedTestViewModel.state.collectAsState()
        LaunchedEffect(modelState.installed?.path, modelState.installed?.sha256) {
            val previous = host
            previous?.close(); readerScope?.cancel(); host = null
            readerRelease?.await()
            previous?.awaitProviderClosed()
            readerVersion++
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            val elapsed = (android.os.SystemClock.elapsedRealtime() - activityStarted).toDouble()
            withContext(Dispatchers.IO) { db.reader().performance(PerformanceRecord(documentId = "application", name = "activity_first_frame_ms", value = elapsed)) }
        }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let {
            runCatching { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            runImport { val doc = repository.import(it) { bytes -> lifecycleScope.launch { status = "正在导入 ${bytes / 1048576} MiB…" } }; reopen(); selected = doc; documents = withContext(Dispatchers.IO) { db.reader().documents() } }
        } }
        val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let {
            modelViewModel.manager.import(it)
        } }
        val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        val downloadModel: (ModelAsset) -> Unit = { asset ->
            if (android.os.Build.VERSION.SDK_INT >= 33 && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            modelViewModel.manager.download(asset)
        }
        val glossaryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let {
            runImport {
                val value = withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(it)!!.use { input ->
                        val bytes = java.io.ByteArrayOutputStream(); val buffer = ByteArray(8192)
                        while (true) { val count = input.read(buffer); if (count < 0) break; require(bytes.size() + count <= 524288) { "术语表不能超过 512 KiB" }; bytes.write(buffer, 0, count) }
                        canonicalGlossary(String(bytes.toByteArray(), Charsets.UTF_8))
                    }
                }
                settings.glossary = value; status = "术语表已导入"; showSettings = false; reopen()
            }
        } }
        val glossaryExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let {
            runImport { withContext(Dispatchers.IO) { contentResolver.openOutputStream(it)!!.use { out -> out.write(settings.glossary.toByteArray()) } }; status = "术语表已导出" }
        } }
        val speedExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val json = speedTestViewModel.takeExport()
            if (uri != null && json != null) lifecycleScope.launch {
                try {
                    withContext(Dispatchers.IO) { contentResolver.openOutputStream(uri)!!.use { it.write(json.toByteArray(Charsets.UTF_8)) } }
                    status = "测速结果已导出"
                } catch (e: Exception) { status = "测速结果导出失败：${e.message}" }
            }
        }
        val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri -> uri?.let { host?.requestSave(it) } }
        val metrics = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri -> uri?.let { lifecycleScope.launch(Dispatchers.IO) {
            val rows = db.reader().performance()
            val array = JSONArray(); rows.forEach { row -> array.put(JSONObject().put("documentId", row.documentId).put("name", row.name).put("value", row.value).put("timestamp", row.timestamp)) }
            contentResolver.openOutputStream(it)?.use { out -> out.write(JSONObject().put("events", array).toString(2).toByteArray()) }
        } } }
        AppShell(selected != null, modelState, onHome = { reopen(); selected = null },
            onModels = { showModelLibrary = true }, onSettings = { showSettings = true }) {
            val document = selected
            if (document != null) ReaderToolbar(document.name,
                onPage = { host?.command("translateCurrentPage") }, onAll = { host?.command("translateAll") },
                onCancel = { host?.command("cancelTranslateAll") }, onExport = { export.launch("${document.name.removeSuffix(".pdf")}-annotated.pdf") })
            if (document != null && settings.provider == "llama") Text(
                displayStatus(localBackendStatus?.description ?: settings.backendRecoveryNotice.ifEmpty { "请求后端：${settings.inferenceBackend.label}" }),
                Modifier.fillMaxWidth().padding(20.dp, 3.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (document != null && status != "导入 PDF，或打开示例文档") Text(displayStatus(status),
                Modifier.fillMaxWidth().padding(20.dp, 5.dp), style = MaterialTheme.typography.bodySmall,
                color = if (status.contains("失败") || status.contains("无法")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if (speedState.visible || speedState.busy) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(LocalContext.current.getString(R.string.reader_paused), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else if (document == null) {
                LibraryScreen(documents, modelState, importing, status,
                    onImport = { picker.launch(arrayOf("application/pdf")) },
                    onSample = { runImport { selected = repository.sample(); documents = withContext(Dispatchers.IO) { db.reader().documents() } } },
                    onOpen = { selected = it }, onModels = { showModelLibrary = true }, onCancelImport = { importTask?.cancel() })
            } else key(document.id, readerVersion) {
                var source by remember { mutableStateOf<RangeSource?>(null) }
                LaunchedEffect(document.id, readerVersion) {
                    try {
                        readerRelease?.await()
                        source = withContext(Dispatchers.IO) { RangeSource(document, db.reader().draft(document.id)) }
                    }
                    catch (e: Exception) { if (e is CancellationException) throw e; status = "无法打开文档，请重新导入：${e.message}" }
                }
                source?.let { file ->
                    AndroidView(factory = {
                        val config = settings.config()
                        val provider: TranslationProvider = when (config.provider) {
                            "mock" -> MockTranslationProvider()
                            "api" -> OpenAICompatibleProvider(settings.baseUrl, settings.model, settings.apiKey)
                            else -> LlamaCppProvider(settings.modelPath, settings.cpuThreads,
                                requestedBackend = settings.inferenceBackend,
                                onBackendChanged = { value -> lifecycleScope.launch { localBackendStatus = value } },
                                onVulkanSession = settings::markVulkanSession).also { localBackendStatus = it.backendStatus }
                        }
                        val rs = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate); readerScope = rs
                        ReaderHost(this@MainActivity, file, db.reader(), rs, config, provider,
                            report = { status = it }, saveRequested = { export.launch("annotated.pdf") }, chooseLanguage = { showSettings = true },
                            rendererGone = { lifecycleScope.launch { delay(200); reopen() } }).also { host = it }.createWebView()
                    }, modifier = Modifier.fillMaxWidth().weight(1f), onRelease = { it.destroy() })
                }
            }
        }
        if (showSettings) SettingsDialog(modelState = modelState,
            onSpeedTest = { threads, backend -> modelState.installed?.let { model ->
                val previous = host; reopen()
                speedTestViewModel.open(model, threads, backend) { readerRelease?.await(); previous?.awaitProviderClosed() }
            } },
            onImportGlossary = { glossaryPicker.launch(arrayOf("application/json", "text/plain")) },
            onExportGlossary = { glossaryExport.launch("pararead-glossary.json") },
            onMetrics = { metrics.launch("pararead-performance.json") },
            onSave = { showSettings = false; reopen() }, onClose = { showSettings = false })
        if (speedState.visible) SpeedTestDialog(speedState, onStart = speedTestViewModel::start,
            onCancel = speedTestViewModel::cancel, onClose = speedTestViewModel::dismiss,
            onExport = { if (speedTestViewModel.prepareExport()) speedExport.launch("pararead-speed-test.json") })
        if (showModelLibrary) ModelLibraryDialog(modelState, actionsEnabled = !speedState.busy,
            onDownload = downloadModel, onUse = modelViewModel.manager::select,
            onDelete = { modelViewModel.manager.delete(it) }, onRetry = modelViewModel.manager::retry,
            onCancel = modelViewModel.manager::cancel, onImport = { modelPicker.launch(arrayOf("*/*")) },
            onMore = { showModelCatalog = true }, onClose = { showModelLibrary = false })
        if (showModelCatalog) {
            val catalogState by modelCatalogViewModel.state.collectAsState()
            ModelCatalogDialog(catalogState, modelState.installed?.sha256, modelCatalogViewModel::query,
                modelCatalogViewModel::search, modelCatalogViewModel::details, modelCatalogViewModel::back,
                onDownload = { downloadModel(it); modelCatalogViewModel.stop(); showModelCatalog = false },
                onClose = { modelCatalogViewModel.stop(); showModelCatalog = false })
        }
    }
    @OptIn(ExperimentalLayoutApi::class)
    @Composable private fun SettingsDialog(modelState: ModelState, onSpeedTest: (Int, InferenceBackend) -> Unit, onImportGlossary: () -> Unit,
        onExportGlossary: () -> Unit, onMetrics: () -> Unit, onSave: () -> Unit, onClose: () -> Unit) {
    val text = LocalContext.current
        var provider by remember { mutableStateOf(settings.provider) }; var url by remember { mutableStateOf(settings.baseUrl) }
        var model by remember { mutableStateOf(settings.model) }; var apiKey by remember { mutableStateOf(settings.apiKey) }
        var target by remember { mutableStateOf(settings.targetLanguage) }; var source by remember { mutableStateOf(settings.sourceLanguage) }
        var glossary by remember { mutableStateOf(settings.glossary) }; var batch by remember { mutableStateOf(settings.batchSize.toString()) }
        var cpuThreads by remember { mutableStateOf(settings.cpuThreads.toString()) }
        var backend by remember { mutableStateOf(settings.inferenceBackend) }
        LaunchedEffect(modelState.installed?.sha256) { modelState.installed?.let { model = settings.model } }
        var error by remember { mutableStateOf("") }; var advanced by remember { mutableStateOf(false) }
        var range by remember { mutableStateOf(settings.translateRange) }
        AppDialog(text.getString(R.string.translation_settings), onClose, text.getString(R.string.settings_subtitle), AppGlyph.Settings, footer = {
            Button(onClick = {
                try {
                    val languages = setOf("auto", "zh-CN", "zh-TW", "en", "ja", "ko", "fr", "de", "es", "pt", "ru", "it")
                    require(source in languages && target in languages) { text.getString(R.string.choose_valid_language) }
                    canonicalGlossary(glossary); require(batch.toInt() in 1..40)
                    val threads = cpuThreads.toIntOrNull()
                    if (provider == "llama") require(threads != null && threads in 0..CpuThreads.maximum()) { text.getString(R.string.thread_range, CpuThreads.maximum()) }
                    if (provider == "api") require(url.startsWith("https://") || OpenAICompatibleProvider.isLocalHttp(url)) { text.getString(R.string.http_local_only) }
                    settings.provider = provider; settings.baseUrl = url; if (provider == "api") settings.model = model; settings.apiKey = apiKey
                    settings.translateRange = range
                    if (provider == "llama") { settings.cpuThreads = threads!!; settings.inferenceBackend = backend }
                    settings.sourceLanguage = source; settings.targetLanguage = target; settings.glossary = glossary; settings.batchSize = batch.toInt(); onSave()
                } catch (e: Exception) { error = e.message ?: text.getString(R.string.invalid_settings) }
            }, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(24.dp, 12.dp)) { Text(text.getString(R.string.save)) }
        }) {
            SettingsSection(text.getString(R.string.interface_language), AppGlyph.Globe) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("system" to text.getString(R.string.follow_system), "zh-CN" to "简体中文", "en" to "English").forEach { (code, label) ->
                        FilterChip(selected = uiLanguage == code, onClick = { settings.uiLanguage = code; uiLanguage = code },
                            modifier = Modifier.testTag("ui-language-$code"), label = { Text(label) })
                    }
                }
                Text(text.getString(R.string.interface_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SettingsSection(text.getString(R.string.translation_engine), AppGlyph.Model) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("llama" to text.getString(R.string.offline_gguf), "api" to text.getString(R.string.compatible_api), "mock" to text.getString(R.string.mock)).forEach { (value, name) ->
                        FilterChip(selected = provider == value, onClick = { provider = value }, label = { Text(name) })
                    }
                }
                if (provider == "llama") {
                    Text(text.getString(R.string.acceleration), style = MaterialTheme.typography.titleSmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        InferenceBackend.entries.forEach { option ->
                            FilterChip(selected = backend == option, onClick = { backend = option }, label = { Text(if (option == InferenceBackend.VULKAN) text.getString(R.string.vulkan_experimental) else option.label) })
                        }
                    }
                    Text(if (backend == InferenceBackend.VULKAN) text.getString(R.string.vulkan_hint) else text.getString(R.string.cpu_hint),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (settings.backendRecoveryNotice.isNotEmpty()) Text(displayStatus(settings.backendRecoveryNotice), style = MaterialTheme.typography.bodySmall)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(text.getString(R.string.current_model_format, modelState.activeName()), style = MaterialTheme.typography.titleSmall)
                            Text(modelState.installed?.let { text.getString(R.string.thinking_off, modelSize(it.bytes)) } ?: text.getString(R.string.download_to_read), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = { showModelLibrary = true }) { Text(text.getString(R.string.models)) }
                    }
                    if (modelState.busy || modelState.phase == ModelPhase.ERROR) ModelTransferProgress(modelState)
                    OutlinedButton(enabled = modelState.installed != null && !modelState.busy, onClick = {
                        val threads = cpuThreads.toIntOrNull()
                        if (threads == null || threads !in 0..CpuThreads.maximum()) error = text.getString(R.string.thread_range, CpuThreads.maximum())
                        else { error = ""; onSpeedTest(threads, backend) }
                    }, shape = RoundedCornerShape(12.dp)) {
                        AppIcon(AppGlyph.Sliders, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(text.getString(R.string.speed_test))
                    }
                }
                if (provider == "api") {
                    OutlinedTextField(url, { url = it }, Modifier.fillMaxWidth(), label = { Text(text.getString(R.string.api_base)) }, singleLine = true)
                    OutlinedTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(), label = { Text("API Key") }, visualTransformation = PasswordVisualTransformation(), singleLine = true)
                    OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text(text.getString(R.string.api_model)) }, singleLine = true)
                }
            }
            SettingsSection(text.getString(R.string.language_reading), AppGlyph.Globe) {
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    LanguagePicker(text.getString(R.string.source_language), source, { source = it }, Modifier.weight(1f))
                    LanguagePicker(text.getString(R.string.target_language), target, { target = it }, Modifier.weight(1f), autoLabel = text.getString(R.string.follow_system))
                }
                Text(text.getString(R.string.translation_range), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("page" to text.getString(R.string.current_page), "nearby" to text.getString(R.string.nearby_pages), "manual" to text.getString(R.string.manual)).forEach { (value, name) ->
                        FilterChip(selected = range == value, onClick = { range = value }, label = { Text(name) })
                    }
                }
            }
            TextButton(onClick = { advanced = !advanced }) {
                AppIcon(AppGlyph.Sliders, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(if (advanced) text.getString(R.string.hide_advanced) else text.getString(R.string.advanced))
            }
            if (advanced) SettingsSection(text.getString(R.string.performance_glossary), AppGlyph.Sliders) {
                if (provider == "llama") {
                    OutlinedTextField(cpuThreads, { cpuThreads = it }, Modifier.fillMaxWidth(), label = { Text(text.getString(R.string.cpu_threads)) },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true,
                        supportingText = { Text(text.getString(R.string.threads_help, CpuThreads.resolve(0), CpuThreads.maximum())) })
                    TextButton(onClick = { cpuThreads = "0" }) { Text(text.getString(R.string.reset_threads)) }
                }
                OutlinedTextField(batch, { batch = it }, Modifier.fillMaxWidth(), label = { Text(text.getString(R.string.batch_size)) }, singleLine = true)
                OutlinedTextField(glossary, { glossary = it }, Modifier.fillMaxWidth(), label = { Text(text.getString(R.string.glossary_json)) }, minLines = 2)
                FlowRow {
                    TextButton(onClick = onImportGlossary) { Text(text.getString(R.string.import_glossary)) }
                    TextButton(onClick = { try { settings.glossary = canonicalGlossary(glossary); onExportGlossary() } catch (e: Exception) { error = e.message ?: text.getString(R.string.invalid_glossary) } }) { Text(text.getString(R.string.export_glossary)) }
                    TextButton(onClick = onMetrics) { Text(text.getString(R.string.export_metrics)) }
                }
            }
            if (error.isNotEmpty()) Text(displayStatus(error), color = MaterialTheme.colorScheme.error)
        }
    }
    override fun onStop() {
        super.onStop()
        lifecycleScope.launch(Dispatchers.IO) { selected?.let { db.reader().performance(PerformanceRecord(documentId = it.id, name = "app_pss_kib", value = Debug.getPss().toDouble())) } }
    }
    override fun onDestroy() { host?.close(); readerScope?.cancel(); super.onDestroy() }
}
