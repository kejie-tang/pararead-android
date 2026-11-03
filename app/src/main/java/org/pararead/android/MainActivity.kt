package org.pararead.android

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Debug
import androidx.activity.compose.BackHandler
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var releaseForeground: (suspend () -> Unit)? = null
    private var failForeground: (() -> Unit)? = null
    private val foregroundAcquisition = Mutex()
    private var foregroundReady by mutableStateOf(false)
    private var foregroundError by mutableStateOf<String?>(null)
    private var backgroundWork by mutableStateOf<List<WorkInfo>>(emptyList())
    private var selected by mutableStateOf<DocumentRecord?>(null)
    private var readerFullscreen by mutableStateOf(false)
    private var readingOnly by mutableStateOf(false)
    private var modeSwitching by mutableStateOf(false)
    private var readerVersion by mutableIntStateOf(0)
    private var documents by mutableStateOf<List<LibraryDocument>>(emptyList())
    private var status by mutableStateOf("导入 PDF / TXT，或打开示例文档")
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
        readerFullscreen = settings.readerFullscreen
        readingOnly = settings.readingOnly
        if (backendRecoveryChecked.compareAndSet(false, true)) settings.recoverInterruptedVulkan()
        lifecycleScope.launch {
            documents = db.reader().library().first()
            val restored = savedInstanceState?.getString("documentId")
            selected = documents.firstOrNull { it.document.id == restored }?.document
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
        lifecycleScope.launch { db.reader().library().collect { documents = it } }
        lifecycleScope.launch {
            WorkManager.getInstance(this@MainActivity).getWorkInfosByTagFlow(BackgroundTranslations.TAG).collect { backgroundWork = it }
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
    private suspend fun acquireForeground() = foregroundAcquisition.withLock {
        if (releaseForeground == null) {
            val lease = BackgroundTranslations.enterForeground(this)
            releaseForeground = { lease.release() }
            failForeground = lease::failClosed
        }
    }
    private fun failForegroundCleanup() {
        failForeground?.invoke(); failForeground = null; releaseForeground = null
        foregroundReady = false
        foregroundError = applicationContext.withAppLanguage().getString(R.string.translation_cleanup_failed)
        status = foregroundError!!
    }
    private fun switchReadingOnly(enabled: Boolean) {
        if (modeSwitching || enabled == readingOnly) return
        val previous = host
        modeSwitching = true
        lifecycleScope.launch {
            try {
                previous?.flushBeforeModeSwitch()
                if (host !== previous) return@launch
                settings.readingOnly = enabled; readingOnly = enabled; reopen()
            } catch (_: TimeoutCancellationException) {
                status = applicationContext.withAppLanguage().getString(R.string.reader_mode_switch_failed)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                status = applicationContext.withAppLanguage().getString(R.string.reader_mode_switch_failed)
            } finally { modeSwitching = false }
        }
    }
    private fun reopen() {
        val previous = host; val pending = readerRelease
        val previousScope = readerScope
        previous?.prepareToStop()
        host = null; readerScope = null
        readerRelease = cleanupScope.async(start = CoroutineStart.UNDISPATCHED) {
            try {
                try { pending?.await() }
                finally {
                    if (previous != null) previous.stopAndJoin(null) // Keep visible-reader recovery cursor.
                    else previousScope?.coroutineContext?.get(Job)?.cancelAndJoin()
                }
            } catch (e: Throwable) { failForegroundCleanup(); throw e }
            Unit
        }
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
        LaunchedEffect(status) {
            val notice = status
            readerNoticeDuration(notice)?.let { duration ->
                delay(duration)
                if (status == notice) status = ""
            }
        }
        val modelState by modelViewModel.manager.state.collectAsState()
        val speedState by speedTestViewModel.state.collectAsState()
        val needsForeground = selected != null || showSettings || showModelLibrary || showModelCatalog || speedState.visible || speedState.busy || modelState.busy
        LaunchedEffect(needsForeground) {
            if (needsForeground) {
                foregroundReady = false
                foregroundError = null
                try {
                    acquireForeground()
                    foregroundReady = true
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) {
                    foregroundError = applicationContext.withAppLanguage().getString(R.string.translation_cleanup_failed)
                    status = foregroundError!!
                }
            } else {
                foregroundReady = false
                try {
                    readerRelease?.await()
                    withContext(NonCancellable) {
                        releaseForeground?.invoke(); releaseForeground = null; failForeground = null
                    }
                } catch (e: CancellationException) { throw e }
                catch (_: Exception) { failForegroundCleanup() }
            }
        }
        val translationStates = remember(backgroundWork, documents) {
            val latest = BackgroundTranslations.latestByDocument(backgroundWork)
            documents.mapNotNull { item ->
                val work = latest[item.document.id]
                work?.let {
                    val queued = it.state in setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED) ||
                        (it.state == WorkInfo.State.RUNNING && it.progress.getInt("total", 0) == 0)
                    item.document.id to LibraryTranslationState(queued = queued,
                        running = it.state == WorkInfo.State.RUNNING && !queued,
                        error = BackgroundTranslations.failureFor(it, item.translationJob()))
                }
            }.toMap()
        }
        LaunchedEffect(modelState.installed?.path, modelState.installed?.sha256) {
            reopen(); readerRelease?.await()
        }
        LaunchedEffect(Unit) {
            withFrameNanos { }
            val elapsed = (android.os.SystemClock.elapsedRealtime() - activityStarted).toDouble()
            withContext(Dispatchers.IO) { db.reader().performance(PerformanceRecord(documentId = "application", name = "activity_first_frame_ms", value = elapsed)) }
        }
        var textImportUri by remember { mutableStateOf<Uri?>(null) }
        var textEncoding by remember { mutableStateOf<String?>(null) }
        val importPicked: (Uri, String?) -> Unit = { uri, encoding ->
            runImport {
                val doc = repository.import(uri, encoding) { bytes -> lifecycleScope.launch { status = "正在导入 ${bytes / 1048576} MiB…" } }
                reopen(); selected = doc
            }
        }
        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let {
            runCatching { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            lifecycleScope.launch {
                try {
                    if (repository.isTextImport(it)) { textEncoding = null; textImportUri = it }
                    else importPicked(it, null)
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { status = "无法读取文件：${e.message}" }
            }
        } }
        textImportUri?.let { uri ->
            val text = LocalContext.current
            AlertDialog(onDismissRequest = { textImportUri = null },
                title = { Text(text.getString(R.string.text_encoding_title)) },
                text = { Column {
                    Text(text.getString(R.string.text_encoding_hint))
                    listOf(null, "UTF-8", "UTF-16LE", "UTF-16BE", "GB18030", "GBK").forEach { encoding ->
                        TextButton(onClick = { textEncoding = encoding }) {
                            RadioButton(selected = textEncoding == encoding, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(encoding ?: text.getString(R.string.auto_detect))
                        }
                    }
                } },
                confirmButton = { TextButton(onClick = { textImportUri = null; importPicked(uri, textEncoding) }) { Text(text.getString(R.string.text_import_confirm)) } },
                dismissButton = { TextButton(onClick = { textImportUri = null }) { Text(text.getString(R.string.cancel)) } })
        }
        val modelPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let {
            lifecycleScope.launch {
                try { acquireForeground(); modelViewModel.manager.import(it) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { failForegroundCleanup() }
            }
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
        val fullscreen = selected != null && readerFullscreen
        val setFullscreen: (Boolean) -> Unit = { readerFullscreen = it; settings.readerFullscreen = it }
        BackHandler(enabled = selected != null && !showSettings && !showModelLibrary && !showModelCatalog && !speedState.visible) {
            if (fullscreen) setFullscreen(false) else { reopen(); selected = null }
        }
        DisposableEffect(fullscreen) {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            val previousBehavior = controller.systemBarsBehavior
            if (fullscreen) {
                controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                controller.hide(WindowInsetsCompat.Type.systemBars())
            } else controller.show(WindowInsetsCompat.Type.systemBars())
            onDispose {
                controller.systemBarsBehavior = previousBehavior
                controller.show(WindowInsetsCompat.Type.systemBars())
            }
        }
        Box(Modifier.fillMaxSize()) {
        AppShell(selected != null, modelState, onHome = { reopen(); selected = null },
            onModels = { showModelLibrary = true }, onSettings = { showSettings = true }, fullscreen = fullscreen) {
            val document = selected
            if (document != null && !fullscreen) ReaderToolbar(document.name,
                onPage = { host?.command("translateCurrentPage") }, onAll = { host?.command("translateAll") },
                onCancel = { host?.command("cancelTranslateAll") }, onExport = { export.launch("${document.name.removeSuffix(".pdf")}-annotated.pdf") },
                canExport = !document.isText, readingOnly = readingOnly, modeSwitching = modeSwitching,
                onReadingOnlyChange = ::switchReadingOnly,
                onFullscreen = { setFullscreen(true) })
            if (document != null && !fullscreen && !readingOnly && settings.provider == "llama") Text(
                displayStatus(localBackendStatus?.description ?: settings.backendRecoveryNotice.ifEmpty { "请求后端：${settings.inferenceBackend.label}" }),
                Modifier.fillMaxWidth().padding(20.dp, 3.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (document != null && !fullscreen && status.isNotBlank() && status != "导入 PDF / TXT，或打开示例文档") Text(displayStatus(status),
                Modifier.fillMaxWidth().padding(20.dp, 5.dp), style = MaterialTheme.typography.bodySmall,
                color = if (status.contains("失败") || status.contains("无法")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            if (speedState.visible || speedState.busy || (document != null && !foregroundReady)) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text(foregroundError ?: LocalContext.current.getString(
                    if (document != null && !foregroundReady) R.string.translation_stopping else R.string.reader_paused), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else if (document == null) {
                LibraryScreen(documents, modelState, importing, status,
                    onImport = { picker.launch(arrayOf("application/pdf", "text/plain", "application/octet-stream")) },
                    onSample = { runImport { selected = repository.sample() } },
                    onOpen = { selected = it }, onModels = { showModelLibrary = true }, onCancelImport = { importTask?.cancel() },
                    onRename = { doc, name -> repository.rename(doc, name) },
                    onDelete = { doc -> readerRelease?.await(); BackgroundTranslations.cancelAndAwait(this@MainActivity, doc.id); repository.delete(doc) },
                    translationStates = translationStates,
                    onTranslateAll = { doc -> lifecycleScope.launch {
                        try {
                            readerRelease?.await()
                            if (selected != null || showSettings || showModelLibrary || showModelCatalog || speedTestViewModel.state.value.visible || speedTestViewModel.state.value.busy || modelViewModel.manager.state.value.busy) {
                                status = applicationContext.withAppLanguage().getString(R.string.translation_foreground_active)
                                return@launch
                            }
                            // A just-closed reader may still be releasing its native model.
                            withContext(NonCancellable) { releaseForeground?.invoke(); releaseForeground = null; failForeground = null }
                            val outcome = BackgroundTranslations.enqueue(this@MainActivity, doc.id)
                            status = applicationContext.withAppLanguage().getString(when (outcome) {
                                BackgroundTranslations.EnqueueResult.ENQUEUED -> R.string.translation_enqueued
                                BackgroundTranslations.EnqueueResult.ALREADY_QUEUED -> R.string.translation_already_queued
                                BackgroundTranslations.EnqueueResult.FOREGROUND_ACTIVE -> R.string.translation_foreground_active
                                BackgroundTranslations.EnqueueResult.INVALID_CONFIG -> R.string.translation_invalid_config
                                BackgroundTranslations.EnqueueResult.CLEANUP_FAILED -> R.string.translation_cleanup_failed
                            })
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { status = "无法启动全部翻译：${e.message?.take(180)}" }
                    } },
                    onCancelTranslation = { doc -> lifecycleScope.launch {
                        status = applicationContext.withAppLanguage().getString(R.string.translation_stopping)
                        try {
                            BackgroundTranslations.cancelAndAwait(this@MainActivity, doc.id)
                            status = applicationContext.withAppLanguage().getString(R.string.translation_cancelled)
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { status = "无法停止翻译：${e.message?.take(180)}" }
                    } })
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
                    val showControlsLabel = LocalContext.current.getString(R.string.reader_show_controls)
                    AndroidView(factory = {
                        val runtime = settings.runtimeSnapshot()
                        val config = runtime.config
                        val provider: TranslationProvider = if (readingOnly) MockTranslationProvider() else when (config.provider) {
                            "mock" -> MockTranslationProvider()
                            "api" -> OpenAICompatibleProvider(runtime.baseUrl, config.model, runtime.apiKey)
                            else -> LlamaCppProvider(runtime.modelPath, runtime.cpuThreads,
                                requestedBackend = runtime.backend,
                                onBackendChanged = { value -> lifecycleScope.launch { localBackendStatus = value } },
                                onVulkanSession = settings::markVulkanSession).also { localBackendStatus = it.backendStatus }
                        }
                        val rs = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate); readerScope = rs
                        ReaderHost(this@MainActivity, file, db.reader(), rs, config, provider,
                            report = { status = it }, saveRequested = { export.launch("annotated.pdf") }, chooseLanguage = { showSettings = true },
                            rendererGone = { lifecycleScope.launch { delay(200); reopen() } }).also { host = it }.createWebView()
                    }, update = { host?.setFullscreen(fullscreen, showControlsLabel) },
                        modifier = Modifier.fillMaxWidth().weight(1f), onRelease = { it.destroy() })
                }
            }
        }
        if (fullscreen) {
            val exitFullscreen = LocalContext.current.getString(R.string.reader_exit_fullscreen)
            SmallFloatingActionButton(onClick = { setFullscreen(false) },
                modifier = Modifier.align(Alignment.BottomEnd).safeDrawingPadding().padding(12.dp)
                    .testTag("reader-exit-fullscreen").semantics { contentDescription = exitFullscreen }) {
                AppIcon(AppGlyph.Close)
            }
        }
        }
        if ((showSettings || showModelLibrary || showModelCatalog) && !foregroundReady) AlertDialog(
            onDismissRequest = { showSettings = false; showModelLibrary = false; showModelCatalog = false },
            confirmButton = {}, text = { Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(24.dp)); Text(foregroundError ?: LocalContext.current.getString(R.string.translation_stopping))
            } })
        if (showSettings && foregroundReady) SettingsDialog(modelState = modelState,
            onSpeedTest = { threads, backend -> modelState.installed?.let { model ->
                val previous = host; reopen()
                speedTestViewModel.open(model, threads, backend) { readerRelease?.await(); previous?.awaitProviderClosed() }
            } },
            onImportGlossary = { glossaryPicker.launch(arrayOf("application/json", "text/plain")) },
            onExportGlossary = { glossaryExport.launch("pararead-glossary.json") },
            onMetrics = { metrics.launch("pararead-performance.json") },
            onSave = { showSettings = false; reopen() }, onClose = { showSettings = false })
        if (speedState.visible) SpeedTestDialog(speedState, onStart = { if (foregroundReady) speedTestViewModel.start() },
            canStart = foregroundReady, onCancel = speedTestViewModel::cancel, onClose = speedTestViewModel::dismiss,
            onExport = { if (speedTestViewModel.prepareExport()) speedExport.launch("pararead-speed-test.json") })
        if (showModelLibrary && foregroundReady) ModelLibraryDialog(modelState, actionsEnabled = !speedState.busy,
            onDownload = downloadModel, onUse = modelViewModel.manager::select,
            onDelete = { modelViewModel.manager.delete(it) }, onRetry = modelViewModel.manager::retry,
            onCancel = modelViewModel.manager::cancel, onImport = { modelPicker.launch(arrayOf("*/*")) },
            onMore = { showModelCatalog = true }, onClose = { showModelLibrary = false })
        if (showModelCatalog && foregroundReady) {
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
                Text(text.getString(R.string.translation_range_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
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
    override fun onDestroy() {
        reopen()
        val speedModel = speedTestViewModel
        val modelManager = modelViewModel.manager
        cleanupScope.launch {
            try {
                readerRelease?.await()
                // ViewModels survive recreation; their native/model mutations must retain admission.
                speedModel.awaitCurrentOperation()
                modelManager.awaitLocalMutation()
                releaseForeground?.invoke(); releaseForeground = null; failForeground = null
            } catch (_: Exception) { failForegroundCleanup() }
            finally {
                withContext(NonCancellable) { db.close() }
                cleanupScope.cancel()
            }
        }
        super.onDestroy()
    }
}
