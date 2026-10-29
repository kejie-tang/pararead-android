package org.pararead.android

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.assertDoesNotExist
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
            if (evaluate(scenario, "Boolean($js)") == "true") return
            Thread.sleep(200)
        }
        fail("Reader condition timed out: $js")
    }
    private fun sample() = Intent(context, MainActivity::class.java)
        .putExtra("sample", true).putExtra("mock", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

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
                evaluate(scenario, "window.__bilingual.translations.reset(); window.__bilingual.store.ensure(1).then(p=>window.__restoreSentences=p.blocks.filter(b=>b.kind==='text').flatMap(b=>b.sentences))")
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
            waitFor(scenario, "window.paraRead?.opened")
            evaluate(scenario, "window.__fullscreenMarker='same-webview'")
            var original: WebView? = null
            scenario.onActivity { original = find(it.window.decorView) }
            compose.onNodeWithText("Full screen").performClick()
            compose.onNodeWithTag("reader-exit-fullscreen").assertIsDisplayed()
            compose.onNodeWithText("Full screen").assertDoesNotExist()
            compose.onNodeWithText("Settings").assertDoesNotExist()
            scenario.onActivity { assertSame("Toggling fullscreen must not recreate the reader", original, find(it.window.decorView)) }
            assertEquals("\"same-webview\"", evaluate(scenario, "window.__fullscreenMarker"))
            assertTrue(settings.readerFullscreen)
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
}
