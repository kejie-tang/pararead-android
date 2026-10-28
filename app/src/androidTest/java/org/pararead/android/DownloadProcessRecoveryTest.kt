package org.pararead.android

import android.content.Context
import android.content.ContextWrapper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.pararead.core.ModelAsset
import java.io.File

/** Two opt-in invocations, with an actual adb force-stop between them. Uses isolated test storage. */
@RunWith(AndroidJUnit4::class)
class DownloadProcessRecoveryTest {
    @Test fun transferAcrossProcessBoundary(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val phase = args.getString("recoveryPhase")
        assumeTrue("Opt in with a local fixture and recoveryPhase=prepare|recover", phase in setOf("prepare", "recover"))
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = File(base.cacheDir, "alpha11-process-recovery").apply { mkdirs() }
            override fun getSharedPreferences(name: String?, mode: Int) = base.getSharedPreferences("alpha11-recovery-$name", mode)
        }
        val reportFile = File(base.filesDir, "download-process-recovery.json")
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val manager = ModelManager(context, scope)
            if (phase == "prepare") {
                val asset = ModelAsset("process-recovery-fixture", args.getString("recoveryUrl")!!,
                    args.getString("recoveryBytes")!!.toLong(), args.getString("recoverySha256")!!)
                withContext(Dispatchers.Main) { manager.download(asset) }
                withTimeout(30000) { manager.state.first { it.completed > 0 || it.phase == ModelPhase.ERROR } }
                assertNotEquals(manager.state.value.message, ModelPhase.ERROR, manager.state.value.phase)
                val part = File(context.filesDir, "models").listFiles()!!.single { it.extension == "part" }
                assertTrue(part.length() in 1 until asset.bytes)
                reportFile.writeText(JSONObject().put("partialBytesBeforeStop", part.length()).put("expectedBytes", asset.bytes)
                    .put("modelSha256", asset.sha256).put("workerId", context.getSharedPreferences("model-download", 0).getString("work", "")).toString(2))
            } else {
                val report = JSONObject(reportFile.readText())
                val ready = withTimeout(120000) { manager.state.first { (!it.busy && it.installed != null) || it.phase == ModelPhase.ERROR } }
                assertNotEquals(ready.message, ModelPhase.ERROR, ready.phase)
                assertEquals(report.getString("modelSha256"), ready.installed!!.sha256)
                assertEquals(report.getLong("expectedBytes"), File(ready.installed.path).length())
                assertEquals(1, File(context.filesDir, "models").listFiles()!!.size)
                report.put("recovered", true).put("singlePublishedFile", true)
                reportFile.writeText(report.toString(2))
                context.filesDir.deleteRecursively()
                listOf("settings", "model-download", "model-library").forEach { base.deleteSharedPreferences("alpha11-recovery-$it") }
            }
        } finally { scope.cancel() }
    }
}
