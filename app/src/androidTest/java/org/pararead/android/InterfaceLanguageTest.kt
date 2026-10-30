package org.pararead.android

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InterfaceLanguageTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun capture(name: String) {
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(context.filesDir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun menusSwitchImmediatelyPersistAndKeepTranslationSettings() {
        val settings = Settings(context); val original = settings.uiLanguage
        val originalProvider = settings.provider
        settings.provider = "llama"
        val fingerprint = settings.config().fingerprint()
        try {
            settings.uiLanguage = "zh-CN"
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                capture("library-zh")
                compose.onNodeWithText("设置").performClick()
                compose.onNodeWithTag("ui-language-en").performClick()
                compose.onNodeWithText("Translation settings").assertExists()
                compose.onNodeWithText("Interface language").assertExists()
                compose.onNodeWithText("Detect automatically").assertExists()
                capture("settings-en")
                compose.onNodeWithText("Speed test").performScrollTo().assertExists()
                assertEquals("en", settings.uiLanguage)
                assertEquals(fingerprint, settings.config().fingerprint())
                compose.onNodeWithText("Close").performClick()
                compose.onNodeWithText("Models").performClick()
                compose.onAllNodesWithText("Source and license").onFirst().performScrollTo().assertExists()
                compose.onNodeWithText("Close").performClick()
                scenario.recreate()
                capture("library-en")
                compose.onNodeWithText("Settings").performClick()
                compose.onNodeWithTag("ui-language-en").assertIsSelected()
                compose.onNodeWithTag("ui-language-zh-CN").performClick()
                compose.onNodeWithText("翻译设置").assertExists()
                compose.onNodeWithText("界面语言").assertExists()
                capture("settings-zh")
                assertEquals(fingerprint, settings.config().fingerprint())
            }
        } finally { settings.uiLanguage = original; settings.provider = originalProvider }
    }

    @Test fun changingMenuLanguageKeepsTheOpenReaderInstance() {
        val settings = Settings(context); val original = settings.uiLanguage; val provider = settings.provider
        val fullscreen = settings.readerFullscreen
        fun find(view: View): WebView? = if (view is WebView) view else if (view is ViewGroup)
            (0 until view.childCount).firstNotNullOfOrNull { find(view.getChildAt(it)) } else null
        try {
            settings.uiLanguage = "zh-CN"
            settings.readerFullscreen = false
            val intent = Intent(context, MainActivity::class.java).putExtra("sample", true).putExtra("mock", true)
            ActivityScenario.launch<MainActivity>(intent).use { scenario ->
                var before: WebView? = null
                compose.waitUntil(15000) { scenario.onActivity { before = find(it.window.decorView) }; before != null }
                compose.onNodeWithText("设置").performClick()
                compose.onNodeWithTag("ui-language-en").performClick()
                compose.onNodeWithText("Close").performClick()
                compose.onNodeWithText("This page").assertExists()
                scenario.onActivity { assertSame(before, find(it.window.decorView)) }
            }
        } finally { settings.uiLanguage = original; settings.provider = provider; settings.readerFullscreen = fullscreen }
    }
}
