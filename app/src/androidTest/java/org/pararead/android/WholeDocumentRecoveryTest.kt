package org.pararead.android

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.pararead.core.*
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class WholeDocumentRecoveryTest {
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
        assertTrue(latch.await(8, TimeUnit.SECONDS)); return result
    }
    private fun waitFor(scenario: ActivityScenario<MainActivity>, js: String) {
        val deadline = System.currentTimeMillis() + 60000
        while (System.currentTimeMillis() < deadline) {
            if (evaluate(scenario, "Boolean($js)") == "true") return
            Thread.sleep(100)
        }
        fail("Reader timed out: $js; " + evaluate(scenario, "document.body.innerText.slice(-300)"))
    }
    private fun command(scenario: ActivityScenario<MainActivity>, type: String) {
        scenario.onActivity { activity ->
            val field = MainActivity::class.java.getDeclaredField("host").apply { isAccessible = true }
            (field.get(activity) as ReaderHost).command(type)
        }
    }
    private fun job(db: ReaderDatabase, key: String) = runBlocking { db.reader().job(key)?.let { WholeDocumentJob.parse(it.json) } }
    private fun waitJob(db: ReaderDatabase, key: String, status: WholeJobStatus): WholeDocumentJob {
        val deadline = System.currentTimeMillis() + 90000
        while (System.currentTimeMillis() < deadline) {
            job(db, key)?.let { if (it.status == status) return it }; Thread.sleep(100)
        }
        error("Job never became $status: ${job(db, key)}")
    }
    private fun withFixture(test: (ActivityScenario<MainActivity>, ReaderDatabase, String, Settings) -> Unit) {
        val settings = Settings(context)
        val old = listOf(settings.provider, settings.translateRange, settings.glossary, settings.targetLanguage)
        val db = ReaderDatabase.open(context)
        val document = runBlocking { DocumentRepository(context, db.reader()).sample() }
        val key = "whole:${document.id}"
        val saved = runBlocking { db.reader().job(key) }
        try {
            settings.provider = "mock"; settings.translateRange = "manual"; settings.targetLanguage = "zh-CN"
            settings.glossary = JSONObject().put("test", "recovery-${System.nanoTime()}").toString()
            runBlocking { db.reader().job(JobRecord(key, document.id, "{}", "test")) }
            val intent = Intent(context, MainActivity::class.java).putExtra("sample", true).putExtra("mock", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                waitFor(scenario, "window.paraRead?.opened && window.__bilingual")
                // Interrupt after page 2 translations commit, but before page 2's durable checkpoint.
                evaluate(scenario, """
                    (()=>{const api=acquireVsCodeApi(), original=api.postMessage.bind(api);
                    api.postMessage=m=>{if(m.type==='translateAllProgress'&&m.done===2){window.__heldCheckpoint=true;return new Promise(()=>{});}return original(m);};})()
                """.trimIndent())
                test(scenario, db, key, settings)
            }
        } finally {
            settings.provider = old[0]; settings.translateRange = old[1]; settings.glossary = old[2]; settings.targetLanguage = old[3]
            runBlocking { if (saved != null) db.reader().job(saved) else db.openHelper.writableDatabase.execSQL("DELETE FROM JobRecord WHERE id = ?", arrayOf(key)) }
            db.close()
        }
    }
    @Test fun interruptedReaderResumesFromDurableCursorAndReusesPartialPageCache() = withFixture { scenario, db, key, settings ->
        command(scenario, "translateAll"); waitFor(scenario, "window.__heldCheckpoint")
        val before = requireNotNull(job(db, key)); assertEquals(WholeJobStatus.RUNNING, before.status); assertEquals(2, before.nextPage)
        val text = evaluate(scenario, "window.__bilingual.store.readyPages().find(p=>p.page===2).blocks.find(b=>b.kind==='text'&&b.sentences.length).sentences[0].text")
        val source = org.json.JSONTokener(text).nextValue() as String
        val cacheKey = contentKey(source, settings.config())
        val cached = runBlocking { db.reader().translation(cacheKey) }; assertNotNull("Partial page committed to Room", cached)
        scenario.recreate()
        waitFor(scenario, "window.paraRead?.opened")
        val completed = waitJob(db, key, WholeJobStatus.COMPLETED)
        assertEquals(before.jobId, completed.jobId)
        assertEquals(completed.total + 1, completed.nextPage); assertEquals(0, completed.failedPages)
        assertEquals("Replayed partial page must use the existing translation", cached, runBlocking { db.reader().translation(cacheKey) })
    }
    @Test fun explicitCancelDoesNotAutoResumeAndChangedConfigurationStartsANewJob() = withFixture { scenario, db, key, settings ->
        command(scenario, "translateAll"); waitFor(scenario, "window.__heldCheckpoint")
        command(scenario, "cancelTranslateAll")
        val cancelled = waitJob(db, key, WholeJobStatus.CANCELLED); assertEquals(2, cancelled.nextPage)
        scenario.recreate(); waitFor(scenario, "window.paraRead?.opened")
        Thread.sleep(400)
        assertEquals(cancelled, job(db, key))
        command(scenario, "translateAll")
        val completed = waitJob(db, key, WholeJobStatus.COMPLETED)
        assertTrue(completed.jobId > cancelled.jobId); assertEquals(completed.total + 1, completed.nextPage)
        val pending = completed.copy(jobId = completed.jobId + 1, nextPage = 5, status = WholeJobStatus.RUNNING)
        runBlocking { db.reader().job(JobRecord(key, key.removePrefix("whole:"), pending.json().toString(), pending.status.name)) }
        settings.targetLanguage = "ja"
        scenario.recreate(); waitFor(scenario, "window.paraRead?.opened")
        Thread.sleep(400)
        assertEquals("Changed language must not resume an old cursor", pending, job(db, key))
        command(scenario, "translateAll")
        val fresh = waitJob(db, key, WholeJobStatus.COMPLETED)
        assertNotEquals(pending.fingerprint, fresh.fingerprint)
        assertEquals(fresh.total + 1, fresh.nextPage)
    }
}
