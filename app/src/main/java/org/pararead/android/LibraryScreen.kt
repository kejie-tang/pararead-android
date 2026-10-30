package org.pararead.android

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.Image
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.launch
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable fun AppShell(reading: Boolean, modelState: ModelState, onHome: () -> Unit, onModels: () -> Unit,
    onSettings: () -> Unit, fullscreen: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    val text = LocalContext.current
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val wide = maxWidth >= 840.dp
        Row(Modifier.fillMaxSize()) {
            if (wide && !fullscreen) Surface(Modifier.width(if (reading) 88.dp else 220.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxSize().padding(if (reading) 10.dp else 20.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.padding(bottom = 34.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = if (reading) Arrangement.Center else Arrangement.spacedBy(12.dp)) {
                        IconTile(AppGlyph.Book, Modifier.size(40.dp), MaterialTheme.colorScheme.primary)
                        if (!reading) Text("ParaRead", style = MaterialTheme.typography.titleLarge, letterSpacing = (-.6).sp)
                    }
                    NavigationItem(text.getString(R.string.library), AppGlyph.Book, true, reading, onHome)
                    NavigationItem(text.getString(R.string.models), AppGlyph.Model, false, reading, onModels)
                    NavigationItem(text.getString(R.string.settings), AppGlyph.Settings, false, reading, onSettings)
                    Spacer(Modifier.weight(1f))
                    if (!reading) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text(text.getString(R.string.local_model), Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(modelState.activeName(), style = MaterialTheme.typography.titleSmall)
                        Text(if (modelState.installed == null) text.getString(R.string.choose_a_model) else text.getString(R.string.ready_locally), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (!wide && !fullscreen) Row(Modifier.fillMaxWidth().padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onHome, contentPadding = PaddingValues(4.dp)) {
                        AppIcon(AppGlyph.Book); Spacer(Modifier.width(8.dp)); Text(if (reading) text.getString(R.string.library) else "ParaRead", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onModels) { Text(text.getString(R.string.models)) }
                    TextButton(onClick = onSettings) { Text(text.getString(R.string.settings)) }
                }
                content()
            }
        }
    }
}

@Composable private fun NavigationItem(label: String, glyph: AppGlyph, selected: Boolean, compact: Boolean, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(13.dp), color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant) {
        if (compact) Column(Modifier.fillMaxWidth().padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AppIcon(glyph); Text(label, fontSize = 10.sp, maxLines = 1)
        } else Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            AppIcon(glyph); Text(label, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable fun LibraryScreen(documents: List<LibraryDocument>, modelState: ModelState, importing: Boolean, status: String,
    onImport: () -> Unit, onSample: () -> Unit, onOpen: (DocumentRecord) -> Unit, onModels: () -> Unit, onCancelImport: () -> Unit,
    onRename: suspend (DocumentRecord, String) -> Unit, onDelete: suspend (DocumentRecord) -> Unit) {
    val text = LocalContext.current
    val scope = rememberCoroutineScope()
    var rename by remember { mutableStateOf<DocumentRecord?>(null) }
    var delete by remember { mutableStateOf<DocumentRecord?>(null) }
    var title by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val continuing = documents.firstOrNull { it.reading().let { reading -> reading.has("page") || reading.optInt("pageCount") > 0 } }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        BoxWithConstraints(Modifier.widthIn(max = 1000.dp).fillMaxSize()) {
            val padding = if (maxWidth < 600.dp) 20.dp else 36.dp
            LazyColumn(Modifier.fillMaxSize().testTag("library-list"), contentPadding = PaddingValues(padding),
                verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(text.getString(R.string.my_library), style = MaterialTheme.typography.headlineMedium)
                            Text(pluralStringResource(R.plurals.document_count, documents.size, documents.size), Modifier.padding(top = 4.dp),
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(onClick = onImport, shape = RoundedCornerShape(12.dp)) {
                            AppIcon(AppGlyph.Plus, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(text.getString(R.string.import_pdf))
                        }
                    }
                }
                if (continuing != null) item {
                    ContinueReading(continuing) { onOpen(continuing.document) }
                }
                if (documents.isEmpty()) item {
                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                        Column(Modifier.fillMaxWidth().padding(28.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                            Text(text.getString(R.string.read_across_languages), style = MaterialTheme.typography.titleLarge)
                            Text(text.getString(R.string.library_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                            OutlinedButton(onClick = onSample) { Text(text.getString(R.string.open_sample)) }
                        }
                    }
                }
                if (modelState.installed == null || modelState.busy || modelState.phase == ModelPhase.ERROR) item {
                    Surface(onClick = onModels, shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.surface) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            IconTile(AppGlyph.Model)
                            Column(Modifier.weight(1f)) {
                                Text(if (modelState.busy) text.getString(R.string.model_preparing) else if (modelState.phase == ModelPhase.ERROR) text.getString(R.string.model_attention) else text.getString(R.string.prepare_model), style = MaterialTheme.typography.titleSmall)
                                if (modelState.busy || modelState.phase == ModelPhase.ERROR) ModelTransferProgress(modelState)
                                else Text(text.getString(R.string.model_choices), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            AppIcon(AppGlyph.Arrow, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (importing || status != "导入 PDF，或打开示例文档") item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (importing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(displayStatus(status), Modifier.weight(1f).padding(start = if (importing) 10.dp else 0.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (importing) TextButton(onClick = onCancelImport) { Text(text.getString(R.string.cancel_import)) }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(text.getString(R.string.all_documents), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                        Text(text.getString(R.string.sorted_recent_reading), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (documents.isEmpty()) item {
                    Text(text.getString(R.string.empty_library_hint), Modifier.padding(vertical = 20.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(documents, key = { it.document.id }) { item ->
                    DocumentRow(item, onClick = { onOpen(item.document) },
                        onRename = { rename = item.document; title = item.document.name; error = false },
                        onDelete = { delete = item.document; error = false })
                }
                if (documents.isNotEmpty()) item {
                    TextButton(onClick = onSample) { Text(text.getString(R.string.open_sample)) }
                }
            }
        }
    }
    rename?.let { document ->
        AlertDialog(onDismissRequest = { if (!busy) rename = null }, title = { Text(text.getString(R.string.rename_document)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it; error = false }, singleLine = true,
                    enabled = !busy, label = { Text(text.getString(R.string.document_name)) }, modifier = Modifier.testTag("document-name"),
                    isError = title.length > 200)
                if (error) Text(text.getString(R.string.document_operation_failed), color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { TextButton(enabled = !busy && title.isNotBlank() && title.trim().length <= 200, onClick = {
                busy = true
                scope.launch {
                    try { onRename(document, title); rename = null }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { error = true }
                    finally { busy = false }
                }
            }) { Text(text.getString(R.string.save_document_name)) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { rename = null }) { Text(text.getString(R.string.cancel)) } })
    }
    delete?.let { document ->
        AlertDialog(onDismissRequest = { if (!busy) delete = null },
            title = { Text(text.getString(R.string.delete_document_title, document.name)) },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(text.getString(R.string.delete_document_text))
                if (error) Text(text.getString(R.string.document_operation_failed), color = MaterialTheme.colorScheme.error)
            } },
            confirmButton = { TextButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    try { onDelete(document); delete = null }
                    catch (e: kotlinx.coroutines.CancellationException) { throw e }
                    catch (_: Exception) { error = true }
                    finally { busy = false }
                }
            }) { Text(text.getString(R.string.delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(enabled = !busy, onClick = { delete = null }) { Text(text.getString(R.string.cancel)) } })
    }
}

@Composable private fun ContinueReading(item: LibraryDocument, onClick: () -> Unit) {
    val text = LocalContext.current
    val reading = remember(item.readingJson) { item.reading() }
    val count = reading.optInt("pageCount")
    val page = reading.optInt("page", 1).coerceAtLeast(1).let { if (count > 0) it.coerceAtMost(count) else it }
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.tertiaryContainer,
        modifier = Modifier.fillMaxWidth().testTag("continue-reading")) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
            DocumentThumbnail(item.document, large = true)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                Text(text.getString(R.string.continue_reading), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
                Text(item.document.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(if (count > 0) text.getString(R.string.reading_page, page, count) else text.getString(R.string.reading_page_only, page), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                if (count > 0) LinearProgressIndicator(progress = { page.toFloat() / count }, modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.tertiary, trackColor = MaterialTheme.colorScheme.tertiary.copy(alpha = .14f), drawStopIndicator = {})
                if (reading.optBoolean("translationsSaved")) SavedTranslationLabel()
            }
            AppIcon(AppGlyph.Arrow, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onTertiaryContainer)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun DocumentRow(item: LibraryDocument, onClick: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    val text = LocalContext.current
    val reading = remember(item.readingJson) { item.reading() }
    var menu by remember { mutableStateOf(false) }
    Surface(onClick = onClick, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth().testTag("document-${item.document.id}")) {
        Row(Modifier.padding(start = 16.dp, top = 14.dp, bottom = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            DocumentThumbnail(item.document)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(item.document.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val count = reading.optInt("pageCount")
                val metadata = if (count > 0) text.getString(R.string.document_page_size, count, documentSize(item.document.length)) else documentSize(item.document.length)
                Text(metadata, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(if (count > 0) text.getString(R.string.reading_page, reading.optInt("page", 1).coerceIn(1, count), count)
                        else if (reading.has("page")) text.getString(R.string.reading_page_only, reading.optInt("page", 1).coerceAtLeast(1)) else text.getString(R.string.not_read_yet),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (reading.optBoolean("translationsSaved")) SavedTranslationLabel()
                }
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("document-menu-${item.document.id}")) {
                    AppIcon(AppGlyph.More, Modifier.size(21.dp).rotate(90f).semantics { contentDescription = text.getString(R.string.document_actions, item.document.name) })
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(text.getString(R.string.rename_document)) }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text(text.getString(R.string.delete), color = MaterialTheme.colorScheme.error) },
                        leadingIcon = { AppIcon(AppGlyph.Trash, tint = MaterialTheme.colorScheme.error) }, onClick = { menu = false; onDelete() })
                }
            }
        }
    }
}

@Composable private fun SavedTranslationLabel() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        AppIcon(AppGlyph.Check, Modifier.size(13.dp), tint = MaterialTheme.colorScheme.tertiary)
        Text(LocalContext.current.getString(R.string.document_translations_saved), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
    }
}

@Composable private fun DocumentThumbnail(document: DocumentRecord, large: Boolean = false) {
    val context = LocalContext.current.applicationContext
    val bitmap by produceState<android.graphics.Bitmap?>(initialValue = null, document.id) {
        value = DocumentThumbnails.load(context, document)
    }
    Surface(Modifier.width(if (large) 64.dp else 46.dp).height(if (large) 88.dp else 64.dp),
        shape = RoundedCornerShape(3.dp), color = Color.White, shadowElevation = 2.dp) {
        val image = bitmap
        if (image != null) Image(image.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize())
        else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { AppIcon(AppGlyph.File, tint = Color(0xFF697386)) }
    }
}
private fun documentSize(bytes: Long): String = if (bytes < 1048576) "${maxOf(1, bytes / 1024)} KB" else String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)

@OptIn(ExperimentalLayoutApi::class)
@Composable fun ReaderToolbar(title: String, onPage: () -> Unit, onAll: () -> Unit, onCancel: () -> Unit, onExport: () -> Unit,
    onFullscreen: () -> Unit) {
    val text = LocalContext.current
    Surface(color = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
            val actions: @Composable () -> Unit = { FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onPage) { Text(text.getString(R.string.translate_page)) }
                TextButton(onClick = onAll) { Text(text.getString(R.string.translate_all)) }
                TextButton(onClick = onCancel) { Text(text.getString(R.string.cancel)) }
                TextButton(onClick = onExport) { Text(text.getString(R.string.save_copy)) }
                TextButton(onClick = onFullscreen) { Text(text.getString(R.string.reader_fullscreen)) }
            } }
            if (maxWidth >= 700.dp) Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f).padding(end = 16.dp), style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                actions()
            } else Column {
                Text(title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                actions()
            }
        }
    }
}
