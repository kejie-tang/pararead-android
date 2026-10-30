package org.pararead.android

import android.content.Intent
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.room.Room
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class DocumentLibraryTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture(label: String): File {
        val file = File(File(context.filesDir, "benchmarks").apply { mkdirs() }, "library-${System.nanoTime()}.pdf")
        val pdf = PdfDocument()
        try {
            for (number in 1..3) {
                val page = pdf.startPage(PdfDocument.PageInfo.Builder(400, 600, number).create())
                page.canvas.drawText("$label - page $number", 30f, 60f, Paint().apply { textSize = 18f })
                page.canvas.drawText("Local reading and saved translations.", 30f, 100f, Paint().apply { textSize = 14f })
                pdf.finishPage(page)
            }
            file.outputStream().use { pdf.writeTo(it) }
        } finally { pdf.close() }
        return file
    }
    @Test fun renameReimportDeleteAndSharedCacheRemainIndependent() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, ReaderDatabase::class.java).build()
        val dao = db.reader(); val repository = DocumentRepository(context, dao)
        val original = fixture("Original ${System.nanoTime()}"); val other = fixture("Other ${System.nanoTime()}")
        try {
            val first = repository.benchmark(original.name); val second = repository.benchmark(other.name)
            dao.opened(first.id, 100); dao.opened(second.id, 200)
            assertEquals(second.id, dao.library().first().first().document.id)
            repository.rename(first, "A renamed paper.pdf")
            dao.reading(ReadingRecord(first.id, "{\"page\":2,\"pageCount\":3,\"translationsSaved\":true}"))
            val again = repository.benchmark(original.name)
            assertEquals(first.id, again.id); assertEquals("A renamed paper.pdf", again.name)
            assertEquals(2, dao.documents().size)
            assertEquals(2, dao.library().first().first { it.document.id == first.id }.reading().getInt("page"))
            dao.opened(first.id, 300)
            assertEquals(first.id, dao.library().first().first().document.id)
            val draft = File(context.filesDir, "draft-library-${System.nanoTime()}.suffix").apply { writeText("draft") }
            dao.draft(DraftRecord(first.id, first.revision, draft.path, draft.length()))
            dao.page(PageRecord(first.revision, 1, json = "{}"))
            dao.page(PageRecord(second.revision, 1, json = "{}"))
            dao.job(JobRecord("whole:${first.id}", first.id, "{}", "CANCELLED"))
            dao.translation(TranslationRecord("shared-sentence", "A sentence", "已保存的译文", "test"))
            assertNotNull(DocumentThumbnails.load(context, first))
            repository.delete(first)
            assertTrue(original.exists())
            assertFalse(File(first.path).exists()); assertFalse(draft.exists())
            assertNull(dao.reading(first.id)); assertNull(dao.draft(first.id)); assertNull(dao.job("whole:${first.id}"))
            assertNull(dao.page(first.revision, 1)); assertNotNull(dao.page(second.revision, 1))
            assertNotNull(dao.translation("shared-sentence")); assertEquals(listOf(second.id), dao.documents().map { it.id })
            assertFalse(File(context.cacheDir, "document-thumbnails/${first.id}-v1.png").exists())
        } finally {
            dao.documents().forEach { repository.delete(it) }
            original.delete(); other.delete(); db.close()
        }
    }
    private fun evaluate(scenario: ActivityScenario<MainActivity>, script: String): String {
        fun find(view: View): WebView? = if (view is WebView) view else if (view is ViewGroup)
            (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) } else null
        val latch = CountDownLatch(1); var result = "null"
        scenario.onActivity { activity -> find(activity.window.decorView)?.evaluateJavascript(script) { result = it; latch.countDown() } ?: latch.countDown() }
        check(latch.await(5, TimeUnit.SECONDS))
        return result
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            File(context.filesDir, name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    @Test fun libraryContinuesAtSavedPageAndSupportsBilingualRenameAndDelete() {
        val settings = Settings(context)
        val language = settings.uiLanguage; val provider = settings.provider
        val range = settings.translateRange; val fullscreen = settings.readerFullscreen
        val db = ReaderDatabase.open(context); val dao = db.reader(); val repository = DocumentRepository(context, dao)
        val original = fixture("Library UI ${System.nanoTime()}")
        val document = runBlocking {
            repository.benchmark(original.name).also {
                repository.rename(it, "A long research paper about reading PDF documents on Android.pdf")
                dao.opened(it.id, System.currentTimeMillis() + 1000)
                dao.reading(ReadingRecord(it.id, "{\"page\":2,\"pageCount\":3,\"translationsSaved\":true}"))
            }
        }
        try {
            settings.uiLanguage = "en"; settings.provider = "mock"; settings.translateRange = "manual"; settings.readerFullscreen = false
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)).use { scenario ->
                compose.waitUntil(15000) { compose.onAllNodesWithTag("continue-reading").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("continue-reading").assertTextContains("Page 2 of 3")
                capture("library-layout-en.png")
                compose.onNodeWithTag("continue-reading").performClick()
                compose.waitUntil(20000) { evaluate(scenario, "window.paraRead?.opened === true") == "true" }
                assertEquals("2", evaluate(scenario, "window.PDFViewerApplication.pdfViewer.currentPageNumber"))
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                compose.waitUntil(10000) { compose.onAllNodesWithTag("library-list").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("library-list").performScrollToNode(hasTestTag("document-menu-${document.id}"))
                compose.onNodeWithTag("document-menu-${document.id}").performClick()
                compose.onNodeWithText("Rename").performClick()
                compose.onNodeWithTag("document-name").performTextReplacement("Renamed research notes.pdf")
                compose.onNodeWithText("Save").performClick()
                compose.waitUntil(10000) { runBlocking { dao.documents().first { it.id == document.id }.name == "Renamed research notes.pdf" } }
                settings.uiLanguage = "zh-CN"; scenario.recreate()
                compose.waitUntil(15000) { compose.onAllNodesWithTag("continue-reading").fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithTag("continue-reading").assertTextContains("第 2 / 3 页")
                capture("library-layout-zh.png")
                compose.onNodeWithTag("library-list").performScrollToNode(hasTestTag("document-menu-${document.id}"))
                compose.onNodeWithTag("document-menu-${document.id}").performClick()
                compose.onNodeWithText("删除").performClick()
                compose.onNodeWithText("删除此 PDF 的 App 内副本、阅读记录与批注草稿。外部原文件和共享译文缓存会保留。").assertExists()
                compose.onNodeWithText("删除").performClick()
                compose.waitUntil(10000) { runBlocking { dao.documents().none { it.id == document.id } } }
                assertTrue(original.exists()); assertFalse(File(document.path).exists())
            }
        } finally {
            settings.uiLanguage = language; settings.provider = provider; settings.translateRange = range; settings.readerFullscreen = fullscreen
            runBlocking { repository.delete(document) }; original.delete(); db.close()
        }
    }
}
