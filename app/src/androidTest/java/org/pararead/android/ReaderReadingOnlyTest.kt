package org.pararead.android

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.SharedPreferences
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.pararead.core.WholeDocumentJob
import org.pararead.core.WholeJobStatus
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real Android/WebView lifecycle with a mock provider; not evidence of JNI/model inference. */
@RunWith(AndroidJUnit4::class)
class ReaderReadingOnlyTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun preferenceDefaultsFalseAndPersistsWithoutChangingManualRangeOrFingerprint() {
        // Separate preference namespace: do not reset the user's settings or touch a model/key.
        val name = "reading-only-test-${System.nanoTime()}"
        val isolated = object : ContextWrapper(context) {
            override fun getSharedPreferences(ignored: String, mode: Int): SharedPreferences =
                context.getSharedPreferences(name, Context.MODE_PRIVATE)
        }
        try {
            val settings = Settings(isolated)
            assertFalse(settings.readingOnly)
            settings.translateRange = "manual"
            val fingerprint = settings.config().fingerprint()
            settings.readingOnly = true
            assertTrue(Settings(isolated).readingOnly)
            assertEquals("manual", settings.translateRange)
            assertEquals(fingerprint, settings.config().fingerprint())
            settings.translateRange = "nearby"
            assertTrue(settings.readingOnly)
            settings.readingOnly = false
            assertFalse(Settings(isolated).readingOnly)
            assertEquals("nearby", settings.translateRange)
            assertEquals(fingerprint, settings.config().fingerprint())
        } finally { context.deleteSharedPreferences(name) }
    }

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
        assertTrue("JavaScript callback", latch.await(8, TimeUnit.SECONDS))
        return result
    }
    private fun waitFor(scenario: ActivityScenario<MainActivity>, js: String) {
        val deadline = System.currentTimeMillis() + 60000
        while (System.currentTimeMillis() < deadline) {
            compose.waitForIdle()
            if (evaluate(scenario, "Boolean($js)") == "true") return
            Thread.sleep(100)
        }
        fail("Read-only reader timed out: $js")
    }

    private fun verifyStagingFailureRetry(scenario: ActivityScenario<MainActivity>, host: ReaderHost, dao: ReaderDao) {
        fun field(name: String) = ReaderHost::class.java.getDeclaredField(name).apply { isAccessible = true }
        fun request(type: String, payload: JSONObject): JSONObject {
            evaluate(scenario, "window.__nativeSaveReply=null; window.paraRead.request(${JSONObject.quote(type)},$payload).then(m=>window.__nativeSaveReply=m)")
            waitFor(scenario, "window.__nativeSaveReply")
            return JSONObject(evaluate(scenario, "window.__nativeSaveReply"))
        }
        fun begin(): String {
            val reply = request("document.save", JSONObject().put("incrementalLength", 1))
            assertEquals("save.started", reply.getString("type"))
            return reply.getJSONObject("payload").getString("saveId")
        }
        fun staging() = field("exportTemp").get(host) as java.io.File
        fun assertAborted(temp: java.io.File) {
            assertFalse("Only this private staging path must be removed", temp.exists())
            assertNull(field("saveId").get(host))
            assertNull(field("exportTemp").get(host))
            assertNull(field("exportTarget").get(host))
            assertEquals(0L, field("saveLength").getLong(host))
            assertEquals(0L, field("expectedSaveLength").getLong(host))
        }
        val documentId = host.source.document.id
        val draft = runBlocking { dao.draft(documentId) }
        val cached = runBlocking { dao.translationCount() }
        val first = begin()
        val temp = staging()
        for (type in listOf("save.chunk", "save.end")) {
            val wrong = JSONObject().put("saveId", "wrong-save").put("offset", 0).put("data", "AQ==").put("length", 1)
            assertEquals("protocol.error", request(type, wrong).getString("type"))
            assertEquals("Wrong ID must not abort the active save", first, field("saveId").get(host))
            assertTrue(temp.isFile)
        }
        // Controlled IO failure confined to this transaction's private staging path.
        assertTrue(temp.delete()); assertTrue(temp.mkdir())
        assertEquals("protocol.error", request("save.chunk", JSONObject().put("saveId", first)
            .put("offset", 0).put("data", "AQ==")).getString("type"))
        assertAborted(temp)
        val second = begin() // Same host must no longer reject the retry with SAVE_BUSY.
        val secondTemp = staging()
        assertEquals("protocol.error", request("save.end", JSONObject().put("saveId", second).put("length", 0)).getString("type"))
        assertAborted(secondTemp)
        val third = begin()
        val thirdTemp = staging()
        assertEquals("protocol.error", request("save.chunk", JSONObject().put("saveId", third)
            .put("offset", 1).put("data", "AQ==")).getString("type"))
        assertAborted(thirdTemp)
        assertEquals("Failed staging must not replace a published draft", draft, runBlocking { dao.draft(documentId) })
        assertEquals(cached, runBlocking { dao.translationCount() })
    }

    @Test fun toolbarReopensAndDrainsWhileReadOnlyRefusesRequestsAndPreservesDurableCursor() {
        val settings = Settings(context)
        val oldProvider = settings.provider; val oldRange = settings.translateRange
        val oldMode = settings.readingOnly; val oldLanguage = settings.uiLanguage
        val oldFullscreen = settings.readerFullscreen
        val db = ReaderDatabase.open(context)
        val document = runBlocking { DocumentRepository(context, db.reader()).sample() }
        val key = "whole:${document.id}"
        val savedJob = runBlocking { db.reader().job(key) }
        try {
            settings.provider = "mock"; settings.translateRange = "manual"; settings.readingOnly = true
            settings.uiLanguage = "en"; settings.readerFullscreen = false
            val fingerprint = settings.config().fingerprint()
            // A different config must stay fenced even after leaving read-only mode.
            val cursor = WholeDocumentJob(System.currentTimeMillis(), document.revision, "other-config", 6, nextPage = 2)
            val record = JobRecord(key, document.id, cursor.json().toString(), WholeJobStatus.RUNNING.name)
            runBlocking { db.reader().job(record) }
            val intent = Intent(context, MainActivity::class.java).putExtra("sample", true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                waitFor(scenario, "window.paraRead?.opened")
                evaluate(scenario, "window.paraRead.ready.then(s=>window.__readingOnlySession=s)")
                waitFor(scenario, "window.__readingOnlySession?.settings.readingOnly === true")
                val cached = runBlocking { db.reader().translationCount() }
                compose.onNodeWithTag("reader-reading-only").assertIsSelected()
                compose.onNodeWithText("This page").assertIsNotEnabled()
                compose.onNodeWithText("All / Resume").assertIsNotEnabled()
                compose.onNodeWithText("Cancel").assertIsNotEnabled()
                compose.onNodeWithText("Save a copy").assertIsEnabled()
                compose.onNodeWithText("Full screen").assertIsEnabled()
                assertEquals(record, runBlocking { db.reader().job(key) })
                evaluate(scenario, """
                    Promise.all(['translation.request','translation.cacheGet','job.progress','job.finished']
                    .map(type=>window.paraRead.request(type,{}).then(m=>m.payload.code)))
                    .then(codes=>window.__readingOnlyCodes=codes)
                """.trimIndent())
                waitFor(scenario, "window.__readingOnlyCodes?.length === 4")
                assertEquals("true", evaluate(scenario, "window.__readingOnlyCodes.every(code=>code==='READING_ONLY')"))
                assertEquals(record, runBlocking { db.reader().job(key) })
                assertEquals(cached, runBlocking { db.reader().translationCount() })
                var oldHost: ReaderHost? = null
                scenario.onActivity { activity ->
                    val field = MainActivity::class.java.getDeclaredField("host").apply { isAccessible = true }
                    oldHost = field.get(activity) as ReaderHost
                    oldHost!!.command("translateCurrentPage")
                    oldHost!!.command("translateAll")
                    oldHost!!.command("cancelTranslateAll")
                }
                compose.waitForIdle()
                assertEquals(record, runBlocking { db.reader().job(key) })
                verifyStagingFailureRetry(scenario, requireNotNull(oldHost), db.reader())
                // Intercept only the command, mocking the Web acknowledgement to test native failure handling.
                evaluate(scenario, """
                    window.__modeFlushMarker='old-reader';
                    window.__modeFlushOriginal=window.ParaReadNative.onmessage;
                    window.ParaReadNative.onmessage=e=>{
                      const m=JSON.parse(e.data);
                      if(m.type==='reader.command'&&m.payload.type==='flushBeforeModeSwitch'){
                        window.__heldModeFlush=m.payload.flushId;return;
                      }
                      if(m.type==='reader.command'&&m.payload.type==='resumeAfterModeFlush'){
                        window.__resumedModeFlush=m.payload.flushId;
                      }
                      window.__modeFlushOriginal(e);
                    };
                """.trimIndent())
                // Synthetic active timer: exercise native timer restoration without editing a PDF/model.
                val pendingAutoSave = Job()
                scenario.onActivity {
                    val autoSave = ReaderHost::class.java.getDeclaredField("autoSave").apply { isAccessible = true }
                    (autoSave.get(oldHost) as? Job)?.cancel()
                    autoSave.set(oldHost, pendingAutoSave)
                }
                compose.onNodeWithTag("reader-reading-only").performClick()
                waitFor(scenario, "window.__heldModeFlush")
                assertTrue("Flush must cancel the old delayed autosave", pendingAutoSave.isCancelled)
                compose.onNodeWithTag("reader-reading-only").assertIsSelected().assertIsNotEnabled()
                compose.onNodeWithText("This page").assertIsNotEnabled()
                compose.onNodeWithText("All / Resume").assertIsNotEnabled()
                compose.onNodeWithText("Cancel").assertIsNotEnabled()
                assertTrue("Preference must not change before a matching save acknowledgement", settings.readingOnly)
                evaluate(scenario, "window.paraRead.post('reader.flushed',{flushId:'wrong-flush',success:true})")
                compose.waitForIdle()
                scenario.onActivity { activity ->
                    val field = MainActivity::class.java.getDeclaredField("host").apply { isAccessible = true }
                    assertSame(oldHost, field.get(activity))
                    val pending = ReaderHost::class.java.getDeclaredField("modeFlush").apply { isAccessible = true }
                    assertFalse("Wrong flush ID must not release the barrier", (pending.get(oldHost) as CompletableDeferred<*>).isCompleted)
                    val detached = ReaderHost::class.java.getDeclaredField("detached").apply { isAccessible = true }
                    assertFalse(detached.getBoolean(oldHost))
                }
                evaluate(scenario, "window.paraRead.post('reader.flushed',{flushId:window.__heldModeFlush,success:false,message:'SAVE_FAILED'})")
                waitFor(scenario, "window.__resumedModeFlush===window.__heldModeFlush")
                compose.onNodeWithTag("reader-reading-only").assertIsSelected().assertIsEnabled()
                assertTrue(settings.readingOnly)
                assertEquals("manual", settings.translateRange)
                assertEquals(fingerprint, settings.config().fingerprint())
                assertEquals(record, runBlocking { db.reader().job(key) })
                assertEquals("\"old-reader\"", evaluate(scenario, "window.__modeFlushMarker"))
                scenario.onActivity { activity ->
                    val field = MainActivity::class.java.getDeclaredField("host").apply { isAccessible = true }
                    assertSame("Failed flush must leave the provider/session alive", oldHost, field.get(activity))
                    val closed = ReaderHost::class.java.getDeclaredField("closed").apply { isAccessible = true }
                    assertFalse(closed.getBoolean(oldHost))
                    val autoSave = ReaderHost::class.java.getDeclaredField("autoSave").apply { isAccessible = true }
                    assertNotNull("Failed flush must restore the old autosave responsibility", autoSave.get(oldHost))
                    assertNotSame(pendingAutoSave, autoSave.get(oldHost))
                }
                evaluate(scenario, "window.ParaReadNative.onmessage=window.__modeFlushOriginal")
                // Retry through the real bridge flush/save path, then the existing provider drain.
                compose.onNodeWithTag("reader-reading-only").performClick()
                compose.waitUntil(10000) { !settings.readingOnly }
                compose.onNodeWithTag("reader-reading-only").assertIsNotSelected()
                assertFalse(settings.readingOnly)
                waitFor(scenario, "window.paraRead?.opened")
                evaluate(scenario, "window.paraRead.ready.then(s=>window.__readingOnlySession=s)")
                waitFor(scenario, "window.__readingOnlySession?.settings.readingOnly === false")
                scenario.onActivity { activity ->
                    val field = MainActivity::class.java.getDeclaredField("host").apply { isAccessible = true }
                    assertNotSame(oldHost, field.get(activity))
                    val closed = ReaderHost::class.java.getDeclaredField("closed").apply { isAccessible = true }
                    assertTrue("Old host must finish closing before replacement", closed.getBoolean(oldHost))
                }
                compose.onNodeWithText("This page").assertIsEnabled()
                assertEquals("manual", settings.translateRange)
                assertEquals(fingerprint, settings.config().fingerprint())
                assertEquals(record, runBlocking { db.reader().job(key) })
                compose.onNodeWithTag("reader-reading-only").performClick()
                compose.waitUntil(10000) { settings.readingOnly }
                compose.onNodeWithTag("reader-reading-only").assertIsSelected()
                waitFor(scenario, "window.paraRead?.opened")
                scenario.recreate()
                waitFor(scenario, "window.paraRead?.opened")
                compose.onNodeWithTag("reader-reading-only").assertIsSelected()
                assertEquals(record, runBlocking { db.reader().job(key) })
                assertEquals(cached, runBlocking { db.reader().translationCount() })
            }
        } finally {
            settings.provider = oldProvider; settings.translateRange = oldRange; settings.readingOnly = oldMode
            settings.uiLanguage = oldLanguage; settings.readerFullscreen = oldFullscreen
            runBlocking {
                if (savedJob != null) db.reader().job(savedJob)
                else db.openHelper.writableDatabase.execSQL("DELETE FROM JobRecord WHERE id = ?", arrayOf(key))
            }
            db.close()
        }
    }
}
