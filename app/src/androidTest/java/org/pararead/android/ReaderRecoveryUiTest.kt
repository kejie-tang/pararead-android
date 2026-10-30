package org.pararead.android

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.pararead.core.contentKey
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ReaderRecoveryUiTest {
    @get:Rule val compose = createEmptyComposeRule()
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
        assertTrue(latch.await(8, TimeUnit.SECONDS))
        return result
    }
    private fun waitFor(scenario: ActivityScenario<MainActivity>, js: String) {
        val end = System.currentTimeMillis() + 90000
        while (System.currentTimeMillis() < end) {
            // Advance Compose's test clock so asynchronous document selection can recompose.
            compose.waitForIdle()
            if (evaluate(scenario, "Boolean($js)") == "true") return
            Thread.sleep(200)
        }
        fail("Reader condition timed out: $js")
    }
    private fun sample() = Intent(context, MainActivity::class.java)
        .putExtra("sample", true).putExtra("mock", true)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

    private fun gesture(scenario: ActivityScenario<MainActivity>, selector: String, direction: Int = 0) {
        if (direction == 0) {
            // A WebView consumes a tap during an active fling to stop scrolling.
            var previous = ""; var stable = 0
            for (i in 0 until 25) {
                val position = evaluate(scenario, "[document.querySelector('#viewerContainer').scrollTop,document.querySelector('.bilingualBody').scrollTop]")
                stable = if (position == previous) stable + 1 else 0
                if (stable >= 3) break
                previous = position; Thread.sleep(100)
            }
        }
        val box = JSONObject(evaluate(scenario, "(()=>{const r=document.querySelector(${JSONObject.quote(selector)}).getBoundingClientRect();return {x:r.x,y:r.y,w:r.width,h:r.height,d:devicePixelRatio}})()"))
        var left = 0; var top = 0
        scenario.onActivity { activity ->
            val location = IntArray(2); find(activity.window.decorView)!!.getLocationOnScreen(location)
            left = location[0]; top = location[1]
        }
        val density = box.getDouble("d")
        val x = left + ((box.getDouble("x") + box.getDouble("w") * .55) * density).toInt()
        val from = top + ((box.getDouble("y") + box.getDouble("h") * if (direction > 0) .8 else .3) * density).toInt()
        val to = top + ((box.getDouble("y") + box.getDouble("h") * if (direction > 0) .35 else .75) * density).toInt()
        val command = if (direction == 0) "input tap $x $from" else "input swipe $x $from $x $to 450"
        android.os.ParcelFileDescriptor.AutoCloseInputStream(
            InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
        ).use { it.readBytes() }
    }

    private fun withManualReader(test: (Settings) -> Unit) {
        val settings = Settings(context)
        val provider = settings.provider; val range = settings.translateRange
        val fullscreen = settings.readerFullscreen; val language = settings.uiLanguage
        try {
            settings.provider = "mock"; settings.translateRange = "manual"
            settings.readerFullscreen = false; settings.uiLanguage = "en"
            test(settings)
        } finally {
            settings.provider = provider; settings.translateRange = range
            settings.readerFullscreen = fullscreen; settings.uiLanguage = language
        }
    }

    @Test fun manualReopenRestoresRoomTranslationsWithoutTranslatingCacheMisses() = withManualReader { settings ->
        val db = ReaderDatabase.open(context)
        try {
            ActivityScenario.launch<MainActivity>(sample()).use { scenario ->
                waitFor(scenario, "window.paraRead?.opened && window.__bilingual")
                // Figure labels intentionally render as placeholders rather than sentence spans.
                evaluate(scenario, "window.__bilingual.translations.reset(); window.__bilingual.store.ensure(1).then(p=>window.__restoreSentences=p.blocks.filter(b=>b.kind==='text'&&b.role==='paragraph').flatMap(b=>b.sentences))")
                waitFor(scenario, "window.__restoreSentences?.length > 1")
                val sentence = JSONObject(evaluate(scenario, "window.__restoreSentences[0]"))
                val id = sentence.getString("id"); val text = sentence.getString("text")
                val config = settings.config(); val key = contentKey(text, config)
                val previous = runBlocking { db.reader().translation(key) }
                val marker = "Saved translation — restored without inference"
                try {
                    runBlocking { db.reader().translation(TranslationRecord(key, text, marker, config.fingerprint())) }
                    val document = runBlocking { DocumentRepository(context, db.reader()).sample() }
                    runBlocking { db.reader().reading(ReadingRecord(document.id, "{}")) }
                    val inferenceCount = runBlocking { db.reader().performance().count { it.documentId == document.id && it.name == "translation_ms" } }
                    scenario.recreate()
                    val quotedId = JSONObject.quote(id)
                    waitFor(scenario, "window.paraRead?.opened && window.__bilingual?.translations.state($quotedId).status === 'done'")
                    val translated = JSONTokener(evaluate(scenario, "window.__bilingual.translations.state($quotedId).translation")).nextValue()
                    assertEquals(marker, translated)
                    waitFor(scenario, "window.__bilingual.panel.sentenceElement($quotedId)?.textContent.includes(${JSONObject.quote(marker)})")
                    // Even uncached rows remain idle: restoring a page does not start Mock/API/JNI inference.
                    evaluate(scenario, "window.__bilingual.store.ensure(1).then(p=>window.__restoredPage=p)")
                    waitFor(scenario, "window.__restoredPage")
                    assertEquals("false", evaluate(scenario, "window.__restoredPage.blocks.flatMap(b=>b.sentences).some(s=>window.__bilingual.translations.state(s.id).status==='pending')"))
                    assertEquals(inferenceCount, runBlocking { db.reader().performance().count { it.documentId == document.id && it.name == "translation_ms" } })
                } finally {
                    if (previous != null) runBlocking { db.reader().translation(previous) }
                    else db.openHelper.writableDatabase.execSQL("DELETE FROM TranslationRecord WHERE `key` = ?", arrayOf(key))
                }
            }
        } finally { db.close() }
    }

    @Test fun fullscreenHidesChromeKeepsWebViewAndSupportsExitAndRecreation() = withManualReader { settings ->
        ActivityScenario.launch<MainActivity>(sample()).use { scenario ->
            scenario.onActivity { assertTrue("Sample launch extras must reach the Activity", it.intent.getBooleanExtra("sample", false)) }
            waitFor(scenario, "window.paraRead?.opened")
            evaluate(scenario, "window.__fullscreenMarker='same-webview'")
            var original: WebView? = null
            var beforeWidth = 0; var beforeHeight = 0; var wide = false
            scenario.onActivity {
                original = find(it.window.decorView)
                beforeWidth = original!!.width; beforeHeight = original!!.height
                wide = it.resources.configuration.screenWidthDp >= 840
            }
            compose.onNodeWithText("Full screen").performClick()
            compose.onNodeWithTag("reader-exit-fullscreen").assertIsDisplayed()
            compose.onNodeWithText("Full screen").assertDoesNotExist()
            compose.onNodeWithText("Settings").assertDoesNotExist()
            compose.waitUntil(10000) { original!!.height > beforeHeight + 50 && (!wide || original!!.width > beforeWidth + 50) }
            compose.waitUntil(10000) {
                var hidden = false
                scenario.onActivity { hidden = androidx.core.view.ViewCompat.getRootWindowInsets(it.window.decorView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.systemBars()) == false }
                hidden
            }
            compose.waitForIdle()
            // WebView's native bounds change before Chromium receives its new viewport.
            val afterWidth = original!!.width; val afterHeight = original!!.height
            waitFor(scenario, "Math.abs(innerWidth*devicePixelRatio-$afterWidth)<=2 && Math.abs(innerHeight*devicePixelRatio-$afterHeight)<=2")
            scenario.onActivity { assertSame("Toggling fullscreen must not recreate the reader", original, find(it.window.decorView)) }
            assertEquals("\"same-webview\"", evaluate(scenario, "window.__fullscreenMarker"))
            assertTrue(settings.readerFullscreen)
            if (InstrumentationRegistry.getArguments().getString("captureReaderValidation") == "true") {
                java.io.File(context.filesDir, "reader-fullscreen-bounds.json").writeText(JSONObject()
                    .put("beforeWidth", beforeWidth).put("beforeHeight", beforeHeight)
                    .put("fullscreenWidth", original!!.width).put("fullscreenHeight", original!!.height)
                    .put("wideLayout", wide).put("sameWebView", true).toString(2))
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
                    java.io.File(context.filesDir, "reader-fullscreen.png").outputStream().use {
                        bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
                    }
                    bitmap.recycle()
                }
            }
            compose.onNodeWithTag("reader-exit-fullscreen").performClick()
            compose.onNodeWithText("Full screen").assertIsDisplayed()
            assertFalse(settings.readerFullscreen)
            compose.onNodeWithText("Full screen").performClick()
            scenario.recreate()
            compose.onNodeWithTag("reader-exit-fullscreen").assertIsDisplayed()
            waitFor(scenario, "window.paraRead?.opened")
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.onNodeWithText("Full screen").assertIsDisplayed()
            assertFalse(settings.readerFullscreen)
        }
    }

    @Test fun translatedPageSurvivesLibraryExitWithoutAnotherTranslationRequest() = withManualReader { settings ->
        val db = ReaderDatabase.open(context)
        val oldGlossary = settings.glossary
        val document = runBlocking { DocumentRepository(context, db.reader()).sample() }
        val oldReading = runBlocking { db.reader().reading(document.id) }
        settings.glossary = JSONObject().put("validation", "library-reopen-${System.nanoTime()}").toString()
        val fingerprint = settings.config().fingerprint()
        fun requests() = runBlocking { db.reader().performance().count { it.documentId == document.id && it.name == "translation_ms" } }
        val complete = "window.__bilingual?.store.readyPages().find(p=>p.page===1)?.blocks.filter(b=>b.kind==='text').flatMap(b=>b.sentences).every(s=>window.__bilingual.translations.state(s.id).status==='done')"
        val rows = "window.__bilingual.store.readyPages().find(p=>p.page===1).blocks.filter(b=>b.kind==='text').flatMap(b=>b.sentences).map(s=>({id:s.id,translation:window.__bilingual.translations.state(s.id).translation}))"
        try {
            runBlocking { db.reader().reading(ReadingRecord(document.id, "{}")) }
            ActivityScenario.launch<MainActivity>(sample()).use { scenario ->
                waitFor(scenario, "window.paraRead?.opened && window.__bilingual?.store.readyPages().length > 0")
                val before = requests()
                compose.onNodeWithText("This page").performClick()
                waitFor(scenario, complete)
                compose.waitUntil(10000) { requests() > before }
                val saved = evaluate(scenario, rows)
                assertTrue(saved.contains("模拟译文"))
                val afterTranslation = requests()
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                compose.onNodeWithText("Your library").assertIsDisplayed()
                compose.onNodeWithTag("continue-reading").performClick()
                waitFor(scenario, complete)
                assertEquals(saved, evaluate(scenario, rows))
                compose.onNodeWithText("This page").performClick()
                compose.waitForIdle()
                assertEquals("Reopening and requesting a cached page must not enter the translation queue", afterTranslation, requests())
            }
        } finally {
            settings.glossary = oldGlossary
            db.openHelper.writableDatabase.execSQL("DELETE FROM TranslationRecord WHERE fingerprint = ?", arrayOf(fingerprint))
            runBlocking { if (oldReading != null) db.reader().reading(oldReading) else db.openHelper.writableDatabase.execSQL("DELETE FROM ReadingRecord WHERE documentId = ?", arrayOf(document.id)) }
            db.close()
        }
    }

    @Test fun fullscreenControlsFollowReadingGesturesWithoutRecreatingReader() = withManualReader { _ ->
        ActivityScenario.launch<MainActivity>(sample()).use { scenario ->
            waitFor(scenario, "window.paraRead?.opened && window.__bilingual?.store.readyPages().length > 0")
            compose.onNodeWithText("Full screen").performClick()
            waitFor(scenario, "document.body.classList.contains('readerFullscreen')")
            evaluate(scenario, "window.__bilingual.selection.clear(); window.getSelection().removeAllRanges(); window.__chromeMarker='same-reader'")
            val before = JSONObject(evaluate(scenario, "({pdf:document.querySelector('#viewerContainer').clientHeight,panel:document.querySelector('.bilingualBody').clientHeight})"))
            gesture(scenario, "#viewerContainer", 1)
            waitFor(scenario, "document.body.classList.contains('readerChromeHidden')")
            val after = JSONObject(evaluate(scenario, "({pdf:document.querySelector('#viewerContainer').clientHeight,panel:document.querySelector('.bilingualBody').clientHeight})"))
            assertTrue(after.getInt("pdf") > before.getInt("pdf") + 20)
            assertTrue(after.getInt("panel") > before.getInt("panel") + 20)
            assertEquals("false", evaluate(scenario, "document.querySelector('#readerShowControls').hidden"))
            compose.onNodeWithTag("reader-exit-fullscreen").assertIsDisplayed()
            if (InstrumentationRegistry.getArguments().getString("captureReaderValidation") == "true") {
                java.io.File(context.filesDir, "reader-hidden-controls-bounds.json").writeText(JSONObject().put("before", before).put("after", after).toString(2))
                InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
                    java.io.File(context.filesDir, "reader-hidden-controls.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                }
            }
            gesture(scenario, "#viewerContainer", -1)
            waitFor(scenario, "!document.body.classList.contains('readerChromeHidden')")
            gesture(scenario, ".bilingualBody", 1)
            waitFor(scenario, "document.body.classList.contains('readerChromeHidden')")
            gesture(scenario, "#readerShowControls")
            waitFor(scenario, "!document.body.classList.contains('readerChromeHidden')")
            evaluate(scenario, "window.__bilingual.selection.pin(window.__bilingual.store.readyPages()[0].blocks.filter(b=>b.role==='paragraph').flatMap(b=>b.sentences)[0],'pdf')")
            gesture(scenario, "#viewerContainer", 1)
            assertEquals("false", evaluate(scenario, "document.body.classList.contains('readerChromeHidden')"))
            assertEquals("\"same-reader\"", evaluate(scenario, "window.__chromeMarker"))
            evaluate(scenario, "window.__bilingual.selection.clear()")
            scenario.recreate()
            waitFor(scenario, "window.paraRead?.opened && document.body.classList.contains('readerFullscreen')")
            assertEquals("false", evaluate(scenario, "document.body.classList.contains('readerChromeHidden')"))
            gesture(scenario, "#viewerContainer", 1)
            waitFor(scenario, "document.body.classList.contains('readerChromeHidden')")
            scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            waitFor(scenario, "!document.body.classList.contains('readerFullscreen') && !document.body.classList.contains('readerChromeHidden')")
            compose.onNodeWithText("Full screen").assertIsDisplayed()
        }
    }

    @Test fun fullscreenCanCollapseWhenEnteredBeforeReaderFinishesLoading() = withManualReader { _ ->
        val db = ReaderDatabase.open(context)
        val document = runBlocking { DocumentRepository(context, db.reader()).sample() }
        val previous = runBlocking { db.reader().reading(document.id) }
        try {
            runBlocking { db.reader().reading(ReadingRecord(document.id, "{}")) }
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)).use { scenario ->
                compose.onNodeWithText("Open sample document").performClick()
                compose.onNodeWithText("Full screen").performClick()
                waitFor(scenario, "window.paraRead?.opened")
                waitFor(scenario, "document.body.classList.contains('readerFullscreen')")
                gesture(scenario, "#viewerContainer", 1)
                waitFor(scenario, "document.body.classList.contains('readerChromeHidden')")
                if (InstrumentationRegistry.getArguments().getString("captureReaderValidation") == "true") {
                    Thread.sleep(250)
                    android.os.ParcelFileDescriptor.AutoCloseInputStream(
                        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("screencap -p /sdcard/alpha13-release-reader.png")
                    ).use { it.readBytes() }
                }
                gesture(scenario, "#readerShowControls")
                waitFor(scenario, "!document.body.classList.contains('readerChromeHidden')")
                scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
                waitFor(scenario, "!document.body.classList.contains('readerFullscreen')")
            }
        } finally {
            runBlocking { if (previous != null) db.reader().reading(previous) else db.openHelper.writableDatabase.execSQL("DELETE FROM ReadingRecord WHERE documentId = ?", arrayOf(document.id)) }
            db.close()
        }
    }
}
