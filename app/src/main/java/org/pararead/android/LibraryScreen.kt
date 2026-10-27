package org.pararead.android

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Locale

@Composable fun AppShell(reading: Boolean, modelState: ModelState, onHome: () -> Unit, onModels: () -> Unit,
    onSettings: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding()) {
        val wide = maxWidth >= 840.dp
        Row(Modifier.fillMaxSize()) {
            if (wide) Surface(Modifier.width(if (reading) 88.dp else 220.dp).fillMaxHeight(), color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxSize().padding(if (reading) 10.dp else 20.dp, 28.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.padding(bottom = 34.dp).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = if (reading) Arrangement.Center else Arrangement.spacedBy(12.dp)) {
                        IconTile(AppGlyph.Book, Modifier.size(40.dp), MaterialTheme.colorScheme.primary)
                        if (!reading) Text("ParaRead", style = MaterialTheme.typography.titleLarge, letterSpacing = (-.6).sp)
                    }
                    NavigationItem("书库", AppGlyph.Book, true, reading, onHome)
                    NavigationItem("模型管理", AppGlyph.Model, false, reading, onModels)
                    NavigationItem("设置", AppGlyph.Settings, false, reading, onSettings)
                    Spacer(Modifier.weight(1f))
                    if (!reading) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Text("本机模型", Modifier.padding(top = 12.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(modelState.activeName(), style = MaterialTheme.typography.titleSmall)
                        Text(if (modelState.installed == null) "选择模型，开始离线阅读" else "本机已就绪", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight()) {
                if (!wide) Row(Modifier.fillMaxWidth().padding(16.dp, 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onHome, contentPadding = PaddingValues(4.dp)) {
                        AppIcon(AppGlyph.Book); Spacer(Modifier.width(8.dp)); Text(if (reading) "书库" else "ParaRead", style = MaterialTheme.typography.titleMedium)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onModels) { Text("模型管理") }
                    TextButton(onClick = onSettings) { Text("设置") }
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

@Composable fun LibraryScreen(documents: List<DocumentRecord>, modelState: ModelState, importing: Boolean, status: String,
    onImport: () -> Unit, onSample: () -> Unit, onOpen: (DocumentRecord) -> Unit, onModels: () -> Unit, onCancelImport: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        BoxWithConstraints(Modifier.widthIn(max = 1180.dp).fillMaxSize()) {
            val padding = if (maxWidth < 600.dp) 20.dp else 36.dp
            val showIllustration = maxWidth >= 600.dp
            LazyVerticalGrid(columns = GridCells.Adaptive(260.dp), contentPadding = PaddingValues(padding),
                horizontalArrangement = Arrangement.spacedBy(18.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("我的书库", style = MaterialTheme.typography.headlineMedium)
                            Text("原文与译文，一起读懂。", Modifier.padding(top = 5.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Button(onClick = onImport, shape = RoundedCornerShape(12.dp), contentPadding = PaddingValues(16.dp, 13.dp)) {
                            AppIcon(AppGlyph.Plus, Modifier.size(19.dp)); Spacer(Modifier.width(6.dp)); Text("导入 PDF")
                        }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = .65f)) {
                        Row(Modifier.fillMaxWidth().padding(28.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("让外文阅读更轻松", style = MaterialTheme.typography.titleLarge)
                                Text("导入 PDF，逐句翻译与原文对照。\n也可以先用示例文档体验。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                OutlinedButton(onClick = onSample, shape = RoundedCornerShape(12.dp), border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)) {
                                    AppIcon(AppGlyph.Book, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("打开示例文档")
                                }
                            }
                            if (showIllustration) PaperIllustration(Modifier.size(170.dp, 135.dp))
                        }
                    }
                }
                if (modelState.installed == null || modelState.busy || modelState.phase == ModelPhase.ERROR) item(span = { GridItemSpan(maxLineSpan) }) {
                    Surface(onClick = onModels, shape = RoundedCornerShape(18.dp), color = MaterialTheme.colorScheme.surface) {
                        Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                            IconTile(AppGlyph.Model)
                            Column(Modifier.weight(1f)) {
                                Text(if (modelState.busy) "模型准备中" else if (modelState.phase == ModelPhase.ERROR) "模型需要处理" else "准备你的离线模型", style = MaterialTheme.typography.titleSmall)
                                if (modelState.busy || modelState.phase == ModelPhase.ERROR) ModelTransferProgress(modelState)
                                else Text("轻量快读 0.6B · 质量优先 1.7B", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            AppIcon(AppGlyph.Arrow, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (importing || status != "导入 PDF，或打开示例文档") item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (importing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(status, Modifier.weight(1f).padding(start = if (importing) 10.dp else 0.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        if (importing) TextButton(onClick = onCancelImport) { Text("取消导入") }
                    }
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("最近文档", style = MaterialTheme.typography.titleMedium)
                        Text("${documents.size} 份", Modifier.padding(start = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (documents.isEmpty()) item(span = { GridItemSpan(maxLineSpan) }) {
                    Column(Modifier.fillMaxWidth().padding(vertical = 34.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        IconTile(AppGlyph.File, Modifier.size(52.dp), MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("你的下一篇好文章，从这里开始", style = MaterialTheme.typography.titleSmall)
                        Text("导入 PDF，或先打开示例文档。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                items(documents, key = { it.id }) { document -> DocumentCard(document) { onOpen(document) } }
            }
        }
    }
}

@Composable private fun DocumentCard(document: DocumentRecord, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Surface(onClick = onClick, shape = RoundedCornerShape(20.dp), color = colors.surface, border = BorderStroke(1.dp, colors.outlineVariant)) {
        Column(Modifier.fillMaxWidth().padding(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconTile(AppGlyph.File, Modifier.size(44.dp))
                Spacer(Modifier.weight(1f))
                Surface(shape = RoundedCornerShape(6.dp), color = colors.surfaceVariant) {
                    Text("PDF", Modifier.padding(7.dp, 3.dp), style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
            }
            Text(document.name, style = MaterialTheme.typography.titleMedium, minLines = 2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(documentSize(document.length), style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Spacer(Modifier.weight(1f)); AppIcon(AppGlyph.Arrow, Modifier.size(18.dp), colors.onSurfaceVariant)
            }
        }
    }
}
private fun documentSize(bytes: Long): String = if (bytes < 1048576) "${maxOf(1, bytes / 1024)} KB" else String.format(Locale.ROOT, "%.1f MB", bytes / 1048576.0)

@Composable private fun PaperIllustration(modifier: Modifier) {
    val ink = MaterialTheme.colorScheme.secondary
    val paper = MaterialTheme.colorScheme.surface
    Canvas(modifier) {
        val w = size.width; val h = size.height
        drawRoundRect(ink.copy(alpha = .08f), Offset(w*.2f,h*.12f), Size(w*.65f,h*.83f), CornerRadius(14f))
        drawRoundRect(paper, Offset(w*.12f,0f), Size(w*.62f,h*.9f), CornerRadius(14f))
        drawRoundRect(ink.copy(alpha = .16f), Offset(w*.12f,0f), Size(w*.62f,h*.9f), CornerRadius(14f), style = Stroke(1.5f))
        for (i in 0..5) drawLine(ink.copy(alpha = if (i==2 || i==3) .5f else .17f), Offset(w*.23f,h*(.18f+i*.105f)), Offset(w*(if(i==5) .48f else .62f),h*(.18f+i*.105f)), 4f, androidx.compose.ui.graphics.StrokeCap.Round)
        drawRoundRect(ink, Offset(w*.55f,h*.58f), Size(w*.32f,h*.24f), CornerRadius(12f))
        drawLine(paper, Offset(w*.64f,h*.7f), Offset(w*.76f,h*.7f), 3f, androidx.compose.ui.graphics.StrokeCap.Round)
        drawLine(paper, Offset(w*.7f,h*.64f), Offset(w*.7f,h*.76f), 3f, androidx.compose.ui.graphics.StrokeCap.Round)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable fun ReaderToolbar(title: String, onPage: () -> Unit, onAll: () -> Unit, onCancel: () -> Unit, onExport: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surface) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp)) {
            val actions: @Composable () -> Unit = { FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TextButton(onClick = onPage) { Text("本页") }
                TextButton(onClick = onAll) { Text("全篇 / 继续") }
                TextButton(onClick = onCancel) { Text("取消") }
                TextButton(onClick = onExport) { Text("另存") }
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
