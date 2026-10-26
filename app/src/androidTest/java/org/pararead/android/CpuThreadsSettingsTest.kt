package org.pararead.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.pararead.core.CpuThreads
import org.pararead.llama.LlamaCppProvider

@RunWith(AndroidJUnit4::class)
class CpuThreadsSettingsTest {
    @Test fun settingsReachANewProviderAndDoNotInvalidateTranslations() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = Settings(context); val original = settings.cpuThreads
        val fingerprint = settings.config().fingerprint()
        try {
            val manual = minOf(6, CpuThreads.maximum())
            settings.cpuThreads = manual
            val reopened = Settings(context)
            assertEquals(manual, reopened.cpuThreads)
            LlamaCppProvider("", reopened.cpuThreads).use { assertEquals(manual, it.threadCount) }
            assertEquals(fingerprint, reopened.config().fingerprint())
            settings.cpuThreads = 0
            LlamaCppProvider("", Settings(context).cpuThreads).use { assertEquals(CpuThreads.resolve(0), it.threadCount) }
            try { settings.cpuThreads = CpuThreads.maximum() + 1; fail("Out of range setting accepted") } catch (_: IllegalArgumentException) { }
        } finally { settings.cpuThreads = original.coerceAtMost(CpuThreads.maximum()) }
    }
}
