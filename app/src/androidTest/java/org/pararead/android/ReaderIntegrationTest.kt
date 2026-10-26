package org.pararead.android

import android.content.Intent
import android.webkit.WebView
import android.view.View
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.pararead.llama.NativeBridge
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ReaderIntegrationTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun find(view: View): WebView? {
        if (view is WebView) return view
        if (view is ViewGroup) for (i in 0 until view.childCount) find(view.getChildAt(i))?.let { return it }
        return null
    }
    private fun evaluate(scenario: ActivityScenario<MainActivity>, js: String): String {
        val latch = CountDownLatch(1); var result = "null"
        scenario.onActivity { activity ->
            val web = find(activity.window.decorView)
            if (web == null) latch.countDown() else web.evaluateJavascript(js) { result = it; latch.countDown() }
        }
        assertTrue("JavaScript callback", latch.await(8, TimeUnit.SECONDS)); return result
    }
    private fun waitFor(scenario: ActivityScenario<MainActivity>, js: String, timeout: Long = 90000) {
        val end = System.currentTimeMillis() + timeout
        while (System.currentTimeMillis() < end) { if (evaluate(scenario, "Boolean($js)") == "true") return; Thread.sleep(250) }
        fail("Reader condition timed out: $js; state=" + evaluate(scenario, "({pages:window.PDFViewerApplication?.pdfDocument?.numPages, text:document.body.innerText.slice(-300)})"))
    }
    @Test fun rangesSupportLongOffsetsConcurrentReadsAndDraftBoundary() {
        val base = java.io.File.createTempFile("range-", ".pdf", context.cacheDir)
        val tail = java.io.File.createTempFile("range-", ".suffix", context.cacheDir)
        val length = 3L * 1024 * 1024 * 1024
        try {
            java.io.RandomAccessFile(base, "rw").use { file -> file.setLength(length); file.write("%PDF-1.7".toByteArray()); file.seek(length - 4); file.write(byteArrayOf(1,2,3,4)) }
            tail.writeBytes(byteArrayOf(5,6,7,8))
            val doc = DocumentRecord("test", "test", "test", base.path, "revision", length)
            RangeSource(doc, DraftRecord("test", "revision", tail.path, 4)).use { source ->
                assertArrayEquals(byteArrayOf(1,2,3,4,5,6,7,8),source.read(length-4,length+4))
                val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
                try {
                    val reads = (1..20).map { pool.submit<Boolean> { source.read(length-4,length)[0] == 1.toByte() && String(source.read(0,8)) == "%PDF-1.7" } }
                    assertTrue(reads.all { it.get(5,TimeUnit.SECONDS) })
                } finally { pool.shutdownNow() }
                for ((begin,end) in listOf(-1L to 2L, 0L to 262145L, length to length+5)) {
                    try { source.read(begin,end); fail("Range accepted: $begin..$end") } catch (_: IllegalArgumentException) { }
                }
            }
        } finally { base.delete(); tail.delete() }
    }
    @Test fun arm64JniReaderCacheDraftAndRecovery() {
        if (InstrumentationRegistry.getArguments().getString("skipNative") != "true") assertTrue(NativeBridge.version().contains("2280f0f"))
        val intent = Intent(context, MainActivity::class.java).putExtra("sample", true).putExtra("mock", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ActivityScenario.launch<MainActivity>(intent).use { scenario ->
            waitFor(scenario, "window.__bilingual?.store.readyPages().length > 0")
            waitFor(scenario, "document.querySelector('#viewerContainer').clientHeight > 200 && document.querySelector('.page canvas')?.width > 0")
            waitFor(scenario, "window.paraRead?.opened === true")
            evaluate(scenario, "window.PDFViewerApplication.pdfViewer.currentPageNumber=1; window.__bilingual.translations.translatePage(1)")
            waitFor(scenario, "window.__bilingual.store.readyPages().find(p=>p.page===1)?.blocks.filter(b=>b.kind==='text').flatMap(b=>b.sentences).every(s=>window.__bilingual.translations.state(s.id).status==='done')")
            assertEquals("6", evaluate(scenario, "window.PDFViewerApplication.pdfDocument.numPages"))
            evaluate(scenario, "window.__bilingual.panel.sentenceElement(window.__bilingual.store.readyPages()[0].blocks.filter(b=>b.kind==='text').flatMap(b=>b.sentences).find(s=>s.text.length>70).id).click()")
            waitFor(scenario, "window.__bilingual.selection.current?.source === 'panel'")
            evaluate(scenario, "window.PDFViewerApplication.pdfDocument.annotationStorage.setValue('pdfjs_internal_editor_999',{annotationType:3,pageIndex:0,rect:[70,660,230,690],rotation:0,color:[0,0,0],fontSize:12,value:'Android persisted annotation'}); window.paraRead.save()")
            val db = ReaderDatabase.open(context)
            val deadline = System.currentTimeMillis() + 45000
            var document: DocumentRecord? = null; var draft: DraftRecord? = null
            while (System.currentTimeMillis() < deadline) {
                runBlocking { document = db.reader().documents().firstOrNull(); draft = document?.let { db.reader().draft(it.id) } }
                if (draft != null) break; Thread.sleep(200)
            }
            assertNotNull("Draft persisted", draft)
            assertTrue("Only suffix persisted", draft!!.size in 1..100000)
            assertTrue(runBlocking { db.reader().translationCount() } > 0)
            db.close()
            scenario.recreate()
            waitFor(scenario, "window.__bilingual?.store.readyPages().length > 0")
            evaluate(scenario, "window.__annotations=null;window.PDFViewerApplication.pdfDocument.getPage(1).then(p=>p.getAnnotations()).then(a=>window.__annotations=a)")
            waitFor(scenario, "JSON.stringify(window.__annotations).includes('Android persisted annotation')")
        }
    }
}
