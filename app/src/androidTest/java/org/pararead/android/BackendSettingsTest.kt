package org.pararead.android

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.pararead.llama.InferenceBackend

@RunWith(AndroidJUnit4::class)
class BackendSettingsTest {
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun backendChoicePersistsWithoutInvalidatingTranslations() {
        val settings = Settings(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = settings.inferenceBackend; val originalProvider = settings.provider
        val fingerprint = settings.config().fingerprint()
        try {
            settings.provider = "llama"; settings.inferenceBackend = InferenceBackend.CPU
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithText("设置").performClick()
                compose.onNodeWithText("CPU").assertIsSelected()
                compose.onNodeWithText("Vulkan GPU（实验）").performClick().assertIsSelected()
                compose.onNodeWithText("保存").performClick()
                assertEquals(InferenceBackend.VULKAN, settings.inferenceBackend)
                compose.onNodeWithText("设置").performClick()
                compose.onNodeWithText("Vulkan GPU（实验）").assertIsSelected()
                compose.onNodeWithText("CPU").performClick()
                compose.onNodeWithText("保存").performClick()
                assertEquals(InferenceBackend.CPU, settings.inferenceBackend)
            }
        } finally { settings.inferenceBackend = original; settings.provider = originalProvider }
        assertEquals(fingerprint, settings.config().fingerprint())
    }
    @Test fun interruptedGpuSessionRecoversCpuAndCanBeReenabled() {
        val settings = Settings(InstrumentationRegistry.getInstrumentation().targetContext)
        val original = settings.inferenceBackend
        try {
            settings.inferenceBackend = InferenceBackend.VULKAN
            settings.markVulkanSession(true)
            settings.recoverInterruptedVulkan()
            assertEquals(InferenceBackend.CPU, settings.inferenceBackend)
            assertTrue(settings.backendRecoveryNotice.contains("未正常结束"))
            settings.inferenceBackend = InferenceBackend.VULKAN
            assertEquals("", settings.backendRecoveryNotice)
            settings.markVulkanSession(false)
            settings.recoverInterruptedVulkan()
            assertEquals(InferenceBackend.VULKAN, settings.inferenceBackend)
        } finally { settings.markVulkanSession(false); settings.inferenceBackend = original }
    }
}
