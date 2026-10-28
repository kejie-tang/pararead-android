package org.pararead.android

import android.view.View
import android.view.ViewGroup
import android.webkit.WebView
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.pararead.core.DefaultModel
import org.pararead.core.SpeedTest
import org.pararead.llama.LlamaCppProvider
import org.pararead.llama.InferenceBackend
import java.io.File

@RunWith(AndroidJUnit4::class)
class SpeedTestUiTest {
    @get:Rule val compose = createEmptyComposeRule()
    private fun hasReader(scenario: ActivityScenario<MainActivity>): Boolean {
        fun find(view: View): Boolean = view is WebView || view is ViewGroup && (0 until view.childCount).any { find(view.getChildAt(it)) }
        var found = false
        scenario.onActivity { found = find(it.window.decorView) }
        return found
    }
    @Test fun cancelledCallerFinishesModelRelease(): Unit = runBlocking {
        val provider = LlamaCppProvider("")
        var released = false
        val entered = CompletableDeferred<Unit>()
        val task = launch {
            entered.complete(Unit)
            try { awaitCancellation() } finally { provider.closeAndJoin(); released = true }
        }
        entered.await()
        withTimeout(5_000) { task.cancelAndJoin() }
        assertTrue(released)
    }

    @Test fun missingModelDisablesSpeedTestEntry() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = Settings(context)
        val path = settings.modelPath; val hash = settings.modelHash; val name = settings.model; val provider = settings.provider
        try {
            settings.modelPath = ""; settings.provider = "llama"
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.onNodeWithText("设置").performClick()
                compose.onNodeWithText("速度测试").performScrollTo().assertIsNotEnabled()
            }
        } finally { settings.modelPath = path; settings.modelHash = hash; settings.model = name; settings.provider = provider }
    }

    /** Opt-in actual UI + native engine run; supply ggufPath and the pinned default model. */
    @Test fun realModelResultsSurviveRotationCancellationAndPersistence() {
        val args = InstrumentationRegistry.getArguments()
        val path = args.getString("ggufPath")
        assumeTrue("Supply ggufPath for real-model UI test", path != null)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settings = Settings(context)
        assertEquals(DefaultModel.asset.bytes, File(path!!).length())
        settings.installModel(path, DefaultModel.asset.sha256, "qwen3:1.7b")
        settings.cpuThreads = args.getString("cpuThreads")?.toInt() ?: 0
        settings.inferenceBackend = InferenceBackend.from(args.getString("inferenceBackend"))
        var vm: SpeedTestViewModel? = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { vm = ViewModelProvider(it)[SpeedTestViewModel::class.java] }
            compose.onNodeWithText("打开示例文档").performClick()
            compose.waitUntil(15_000) { hasReader(scenario) }
            compose.onNodeWithText("设置").performClick()
            compose.onNodeWithText("速度测试").performScrollTo().assertIsEnabled().performClick()
            compose.waitUntil(15_000) { vm!!.state.value.phase == SpeedPhase.READY }
            compose.waitUntil(15_000) { !hasReader(scenario) }
            compose.onNodeWithText("开始测速").performClick()
            compose.waitUntil(90_000) { vm!!.state.value.run >= 2 || !vm!!.state.value.busy }
            assertTrue(vm!!.state.value.toString(), vm!!.state.value.run >= 2)
            scenario.recreate()
            scenario.onActivity { assertSame(vm, ViewModelProvider(it)[SpeedTestViewModel::class.java]) }
            compose.onNodeWithText("离线模型速度测试").assertExists()
            compose.waitUntil(360_000) { !vm!!.state.value.busy }
            assertEquals(vm!!.state.value.message, SpeedPhase.COMPLETED, vm!!.state.value.phase)
            val report = vm!!.state.value.report!!
            assertEquals(4, report.result.samples.size)
            assertTrue(report.result.samples.all { it.metrics.generatedTokens > 1 && it.metrics.tokensPerSecond > 0 })
            assertEquals(SpeedTest.LONG_SOURCE, report.toJson().getString("source"))
            assertEquals(3, report.metadata.getJSONArray("engineSamples").length())
            assertTrue(report.metadata.getString("cpuPlugin").startsWith("libggml-cpu"))
            assertTrue(report.metadata.getString("systemInfo").contains("CPU_PLUGIN="))
            assertEquals(settings.inferenceBackend.name, report.metadata.getString("requestedBackend"))
            assertTrue(report.metadata.getString("actualBackend") in setOf("CPU", "VULKAN"))
            if (settings.inferenceBackend == InferenceBackend.VULKAN && report.metadata.getString("actualBackend") == "CPU")
                assertTrue(report.metadata.getString("fallbackReason").isNotEmpty())
            assertTrue(report.result.samples.all { it.metrics.decodeNanos > 0 && it.metrics.samplingNanos > 0 })
            File(context.filesDir, "app-speed-test.json").writeText(report.toJson().toString(2))
            compose.onNodeWithText("生成速度：", substring = true).performScrollTo().assertExists()
            compose.onNodeWithText("展开详细数据与译文").performScrollTo().performClick()
            compose.onNodeWithText("CPU 内核：", substring = true).performScrollTo().assertExists()
            compose.onNodeWithText("导出测速结果 JSON").performScrollTo().assertIsEnabled()
            compose.onNodeWithText("重新测速").performClick()
            compose.waitUntil(15_000) { vm!!.state.value.run >= 1 }
            compose.onNodeWithText("取消测速").performClick()
            compose.waitUntil(30_000) { !vm!!.state.value.busy }
            assertEquals(SpeedPhase.CANCELLED, vm!!.state.value.phase)
            assertSame(report, vm!!.state.value.report)
            val persisted = SavedSpeedReport.fromJson(context.getSharedPreferences("speed-test", 0).getString("last-report", null)!!)
            assertEquals(report.result, persisted.result)
            compose.onNodeWithText("关闭测速").performClick()
            compose.waitUntil(15_000) { hasReader(scenario) }
            File(context.filesDir, "app-speed-test-ui-passed.txt").writeText("Entry, native token metrics, four samples, rotation, cancellation, persistence, export entry and reader resume passed.\n")
        }
    }
}
