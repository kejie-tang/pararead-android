package org.pararead.android

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.pararead.core.DefaultModel
import java.io.File

/** Opt-in: replace the app's installed model with a full-size verified download, left for inference/UI tests. */
@RunWith(AndroidJUnit4::class)
class RealModelDownloadTest {
    @Test fun fullGgufUsesDownloadManagerVerificationAndActivation(): Unit = runBlocking {
        val url = InstrumentationRegistry.getArguments().getString("downloadFixtureUrl")
        assumeTrue("Pass -e downloadFixtureUrl <HTTP fixture URL or production HTTPS URL>", !url.isNullOrEmpty())
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val report = JSONObject().put("date", java.time.LocalDate.now().toString()).put("physicalDevice", false).put("url", url)
            .put("productionUrl", DefaultModel.asset.url).put("fullProductionCdnDownload", url == DefaultModel.asset.url)
        val reportFile = File(context.filesDir, "real-model-download.json")
        try {
            val manager = ModelManager(context, scope, DefaultModel.asset.copy(url = url!!))
            if (manager.state.value.cancellable) {
                withContext(Dispatchers.Main) { manager.cancel() }
                withTimeout(30000) { manager.state.first { !it.busy } }
            }
            if (manager.state.value.installed != null) {
                withContext(Dispatchers.Main) { manager.delete() }
                withTimeout(30000) { manager.state.first { !it.busy && it.installed == null } }
            }
            val started = System.nanoTime()
            withContext(Dispatchers.Main) { manager.download() }
            val ready = withTimeout(300000) { manager.state.first { !it.busy } }
            assertEquals(ready.message, ModelPhase.IDLE, ready.phase)
            val model = requireNotNull(ready.installed)
            assertEquals(DefaultModel.asset.sha256, model.sha256); assertEquals(DefaultModel.asset.bytes, model.bytes)
            assertEquals(model.path, Settings(context).modelPath); assertEquals("llama", Settings(context).provider)
            assertEquals("qwen3:1.7b", Settings(context).model)
            assertEquals(0L, context.getSharedPreferences("model-download", 0).getLong("id", 0))
            report.put("bytes", model.bytes).put("sha256", model.sha256).put("totalMs", (System.nanoTime() - started) / 1e6)
                .put("activated", true).put("transferRecordClearedBeforeReady", true).put("status", "passed")
        } catch (e: Exception) { report.put("status", "failed").put("error", e.toString()); throw e }
        finally { reportFile.writeText(report.toString(2)); scope.cancel() }
    }
}
