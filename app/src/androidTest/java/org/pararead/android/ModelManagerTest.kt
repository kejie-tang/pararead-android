package org.pararead.android

import android.content.Context
import android.content.ContextWrapper
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith
import org.pararead.core.*
import java.io.Closeable
import java.io.File
import java.net.ServerSocket
import java.net.SocketException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.Executors

@RunWith(AndroidJUnit4::class)
class ModelManagerTest {
    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    private class IsolatedContext(base: Context) : ContextWrapper(base) {
        private val id = "model-test-${UUID.randomUUID()}"
        override fun getApplicationContext(): Context = this
        override fun getFilesDir() = File(super.getCacheDir(), id).apply { mkdirs() }
        override fun getExternalFilesDir(type: String?) = File(requireNotNull(super.getExternalFilesDir(type)), id).apply { mkdirs() }
        override fun getSharedPreferences(name: String?, mode: Int) = super.getSharedPreferences("$id-$name", mode)
        fun cleanup() {
            getSharedPreferences("model-download", 0).getString("work", null)?.let {
                androidx.work.WorkManager.getInstance(this).cancelWorkById(UUID.fromString(it)).result.get()
            }
            val rowId = getSharedPreferences("model-download", 0).getLong("id", 0)
            if (rowId > 0) getSystemService(android.app.DownloadManager::class.java).remove(rowId)
            filesDir.deleteRecursively(); getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS).deleteRecursively()
            deleteSharedPreferences("$id-settings"); deleteSharedPreferences("$id-model-download")
        }
    }
    private class ModelServer(@Volatile var bytes: ByteArray, private val throttle: Long = 0) : Closeable {
        val requests = java.util.concurrent.atomic.AtomicInteger()
        private val socket = ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"))
        private val pool = Executors.newCachedThreadPool()
        val url = "http://127.0.0.1:${socket.localPort}/model.gguf"
        init { pool.execute {
            while (!socket.isClosed) {
                val client = try { socket.accept() } catch (_: SocketException) { break }
                pool.execute {
                    try { client.use {
                        requests.incrementAndGet()
                        val reader = it.getInputStream().bufferedReader(); var offset = 0
                        while (true) {
                            val line = reader.readLine() ?: break
                            if (line.isEmpty()) break
                            if (line.startsWith("Range:", true)) offset = line.substringAfter("bytes=").substringBefore('-').toInt()
                        }
                        val output = it.getOutputStream()
                        val range = if (offset > 0) "Content-Range: bytes $offset-${bytes.size - 1}/${bytes.size}\r\n" else ""
                        output.write("HTTP/1.1 ${if (offset > 0) "206 Partial Content" else "200 OK"}\r\nContent-Length: ${bytes.size - offset}\r\nContent-Type: application/octet-stream\r\nAccept-Ranges: bytes\r\n${range}Connection: close\r\n\r\n".toByteArray())
                        while (offset < bytes.size) {
                            val n = minOf(65536, bytes.size - offset); output.write(bytes, offset, n); output.flush(); offset += n
                            if (throttle > 0) Thread.sleep(throttle)
                        }
                    } } catch (_: Exception) { /* cancelled transfer closes the connection */ }
                }
            }
        } }
        override fun close() { socket.close(); pool.shutdownNow() }
    }
    private val payload get() = "GGUF".toByteArray() + ByteArray(8 * 1048576) { (it % 127).toByte() }
    private fun spec(server: ModelServer, bytes: ByteArray) = ModelAsset("qwen3:1.7b", server.url, bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
    private suspend fun ModelManager.await(predicate: (ModelState) -> Boolean) = withTimeout(60000) {
        state.first { predicate(it) || it.phase == ModelPhase.ERROR }.also { check(predicate(it)) { "Unexpected model state: $it" } }
    }

    @Test fun liveHuggingFaceSelectionDownloadsAndVerifies(): Unit = runBlocking {
        assumeTrue("Opt in with hfLive=true", InstrumentationRegistry.getArguments().getString("hfLive") == "true")
        val context = IsolatedContext(base); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val catalog = HuggingFaceCatalog()
            assertTrue(catalog.search("LFM2.5-350M").any { it.id == "LiquidAI/LFM2.5-350M-GGUF" })
            val details = catalog.details("LiquidAI/LFM2.5-350M-GGUF")
            val asset = details.files.single { it.filename == "LFM2.5-350M-Q4_K_M.gguf" }.asset
            val manager = ModelManager(context, scope)
            withContext(Dispatchers.Main) { manager.download(asset) }
            val ready = withTimeout(180000) { manager.state.first { it.installed?.sha256 == asset.sha256 && !it.busy || it.phase == ModelPhase.ERROR } }
            assertEquals(ready.message, asset.sha256, ready.installed?.sha256)
            assertEquals(asset.bytes, File(ready.installed!!.path).length())
            assertEquals("lfm1.0", Settings(context).modelLicense)
            File(base.filesDir, "hf-download-check.json").writeText(org.json.JSONObject().put("status", "passed")
                .put("repository", asset.repository).put("revision", asset.revision).put("sha256", asset.sha256).put("bytes", asset.bytes).toString(2))
        } finally { scope.cancel(); context.cleanup() }
    }

    @Test fun downloadSurvivesMonitorRecreationThenDeletePreservesDocumentsAndCache(): Unit = runBlocking {
        val context = IsolatedContext(base); val bytes = payload
        var scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try { ModelServer(bytes, 20).use { server ->
            val asset = spec(server, bytes).copy(name = "LiquidAI/test · model.gguf", license = "lfm1.0", repository = "LiquidAI/test", revision = "a".repeat(40))
            val first = ModelManager(context, scope)
            withContext(Dispatchers.Main) { first.download(asset) }
            first.await { it.completed > 0 && it.phase == ModelPhase.DOWNLOADING }
            scope.cancel() // The durable worker continues without an Activity or monitor.
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val resumed = ModelManager(context, scope)
            val ready = resumed.await { it.installed?.sha256 == asset.sha256 && !it.busy }
            assertEquals(bytes.size.toLong(), File(ready.installed!!.path).length())
            assertEquals("llama", Settings(context).provider); assertEquals(asset.name, Settings(context).model)
            assertEquals("lfm1.0", Settings(context).modelLicense); assertEquals("LiquidAI/test", Settings(context).modelRepository)
            assertEquals(asset.revision, Settings(context).modelRevision)
            withTimeout(5000) { while (context.getSharedPreferences("model-download", 0).getLong("id", 0) > 0) delay(10) }
            context.getSharedPreferences("model-download", 0).edit().putLong("id", Long.MAX_VALUE).putString("error", "下载任务已移除，请重试").commit()
            val reconciled = ModelManager(context, scope, asset)
            reconciled.await { it.phase == ModelPhase.IDLE && it.installed != null }
            assertEquals("", context.getSharedPreferences("model-download", 0).getString("error", ""))
            val document = File(context.filesDir, "paper.pdf").apply { writeText("keep PDF") }
            val cache = File(context.filesDir, "translations.db").apply { writeText("keep cache") }
            withContext(Dispatchers.Main) { reconciled.delete() }
            reconciled.await { it.message == "模型已删除" && !it.busy }
            assertFalse(File(ready.installed!!.path).exists()); assertEquals("", Settings(context).modelPath)
            assertEquals("keep PDF", document.readText()); assertEquals("keep cache", cache.readText())
        } } finally { scope.cancel(); context.cleanup() }
    }
    @Test fun cancelRemovesTransferAndRetryCanInstall(): Unit = runBlocking {
        val context = IsolatedContext(base); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate); val bytes = payload
        try { ModelServer(bytes, 20).use { server ->
            val manager = ModelManager(context, scope, spec(server, bytes))
            withContext(Dispatchers.Main) { manager.download() }
            manager.await { it.completed > 0 }
            withContext(Dispatchers.Main) { manager.cancel() }
            manager.await { it.message == "已取消" && !it.busy }
            assertTrue(File(context.filesDir, "models").listFiles()!!.isEmpty())
            assertEquals(0L, context.getSharedPreferences("model-download", 0).getLong("id", 0))
            withContext(Dispatchers.Main) { manager.download() }
            manager.await { it.installed != null && !it.busy }
            withContext(Dispatchers.Main) { manager.delete() }; manager.await { it.message == "模型已删除" }
        } } finally { scope.cancel(); context.cleanup() }
    }
    @Test fun downloadPublishesTheSameInodeAndNeverCreatesASecondWeightCopy(): Unit = runBlocking {
        val context = IsolatedContext(base); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val bytes = payload
        try { ModelServer(bytes, 15).use { server ->
            val manager = ModelManager(context, scope, spec(server, bytes))
            withContext(Dispatchers.Main) { manager.download() }
            manager.await { it.completed > 0 && it.phase == ModelPhase.DOWNLOADING }
            val directory = File(context.filesDir, "models")
            val part = directory.listFiles()!!.single { it.extension == "part" }
            val inode = android.system.Os.stat(part.path).st_ino
            while (manager.state.value.busy) {
                assertTrue("Only one weight copy may exist", directory.listFiles()!!.sumOf { it.length() } <= bytes.size.toLong())
                delay(10)
            }
            val ready = manager.state.value.installed!!
            assertEquals(inode, android.system.Os.stat(ready.path).st_ino)
            assertEquals(1, directory.listFiles()!!.size)
            assertTrue(context.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS).listFiles()!!.isEmpty())
        } } finally { scope.cancel(); context.cleanup() }
    }
    @Test fun completedPublishIsRecoveredWithoutAnotherDownload(): Unit = runBlocking {
        val context = IsolatedContext(base); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val bytes = payload
        try { ModelServer(bytes).use { server ->
            val stored = ModelFiles(File(context.filesDir, "models")).install(bytes.inputStream())
            // Simulate process death after rename but before the work result / library is saved.
            val manager = ModelManager(context, scope, spec(server, bytes))
            withContext(Dispatchers.Main) { manager.download() }
            val ready = manager.await { !it.busy && it.installed?.sha256 == stored.sha256 }
            assertEquals(File(stored.path).canonicalPath, File(ready.installed!!.path).canonicalPath)
            assertEquals(0, server.requests.get())
            assertEquals(1, File(context.filesDir, "models").listFiles()!!.size)
        } } finally { scope.cancel(); context.cleanup() }
    }
    @Test fun failedHashKeepsOldModelAndCanRetryWithCorrectDigest(): Unit = runBlocking {
        val context = IsolatedContext(base); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate); val bytes = payload
        try { ModelServer(bytes).use { server ->
            val old = ModelFiles(File(context.filesDir, "models")).install(("GGUF".toByteArray() + ByteArray(32)).inputStream())
            Settings(context).installModel(old.path, old.sha256, "qwen3:1.7b")
            val bad = ModelManager(context, scope, spec(server, bytes).copy(sha256 = "0".repeat(64)))
            withContext(Dispatchers.Main) { bad.download() }
            val error = bad.await { it.phase == ModelPhase.ERROR }
            assertTrue(error.message.contains("SHA-256")); assertEquals(old.path, error.installed!!.path)
            assertTrue(File(old.path).exists()); assertEquals(1, File(context.filesDir, "models").listFiles()!!.size)
            val retry = ModelManager(context, scope, spec(server, bytes))
            withContext(Dispatchers.Main) { retry.download() }; retry.await { it.installed?.sha256 == spec(server, bytes).sha256 && !it.busy }
            assertTrue(File(old.path).exists()) // Other downloaded models remain available.
            withContext(Dispatchers.Main) { retry.delete() }; retry.await { it.message == "模型已删除" }
        } } finally { scope.cancel(); context.cleanup() }
    }
    @Test fun manualGgufImportUsesSameManagedStorage(): Unit = runBlocking {
        val context = IsolatedContext(base); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val input = File(context.filesDir, "input.gguf").apply { writeBytes("GGUF".toByteArray() + ByteArray(32)) }
            val manager = ModelManager(context, scope)
            withContext(Dispatchers.Main) { manager.import(Uri.fromFile(input)) }
            val ready = manager.await { it.installed != null && !it.busy }
            assertTrue(File(ready.installed!!.path).isFile)
            assertEquals("input.gguf", Settings(context).model)
            withContext(Dispatchers.Main) { manager.delete() }; manager.await { it.message == "模型已删除" }
            assertTrue(input.exists())
        } finally { scope.cancel(); context.cleanup() }
    }
    @Test fun upgradeRetainsBothModelsAndSwitchDeleteAreScoped(): Unit = runBlocking {
        val context = IsolatedContext(base); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val original = ModelFiles(File(context.filesDir, "models")).install(("GGUF".toByteArray() + ByteArray(32)).inputStream())
            val alias = File(File(original.path).parentFile, "legacy-custom-name.gguf")
            check(File(original.path).renameTo(alias))
            val legacy = original.copy(path = alias.absolutePath)
            Settings(context).installModel(legacy.path, legacy.sha256, "legacy model", "Apache-2.0", "example/legacy", "a".repeat(40))
            val bytes = payload
            ModelServer(bytes).use { server ->
                val selected = spec(server, bytes).copy(name = "second model", license = "Apache-2.0")
                val manager = ModelManager(context, scope)
                assertEquals(legacy.sha256, manager.state.value.models.single().model.sha256)
                withContext(Dispatchers.Main) { manager.download(selected) }
                manager.await { !it.busy && it.installed?.sha256 == selected.sha256 }
                assertTrue(File(legacy.path).exists()); assertEquals(2, manager.state.value.models.size)
                val restored = ModelManager(context, scope)
                assertEquals(2, restored.state.value.models.size)
                withContext(Dispatchers.Main) { restored.select(legacy.sha256) }
                restored.await { !it.busy && it.installed?.sha256 == legacy.sha256 }
                assertEquals("example/legacy", Settings(context).modelRepository)
                assertEquals("legacy model", Settings(context).model)
                // Deleting the active model clears only the selection, preserving the other file.
                withContext(Dispatchers.Main) { restored.delete(legacy.sha256) }
                restored.await { !it.busy && it.message == "模型已删除" }
                assertNull(restored.state.value.installed)
                assertEquals(selected.sha256, restored.state.value.models.single().model.sha256)
                withContext(Dispatchers.Main) { restored.select(selected.sha256) }
                restored.await { !it.busy && it.installed?.sha256 == selected.sha256 }
                // Downloading a saved asset selects it, without a new system download.
                withContext(Dispatchers.Main) { restored.download(selected) }
                assertEquals(0L, context.getSharedPreferences("model-download", 0).getLong("id", 0))
            }
        } finally { scope.cancel(); context.cleanup() }
    }

    @Test fun retryAfterRecreationKeepsTheFailedSelectionAndOldModel(): Unit = runBlocking {
        val context = IsolatedContext(base); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val old = ModelFiles(File(context.filesDir, "models")).install(("GGUF".toByteArray() + ByteArray(32)).inputStream())
            Settings(context).installModel(old.path, old.sha256, "old model")
            val good = payload; val damaged = good.clone().apply { this[100] = 99 }
            ModelServer(damaged).use { server ->
                val selected = spec(server, good).copy(name = "selected small model")
                val first = ModelManager(context, scope)
                withContext(Dispatchers.Main) { first.download(selected) }
                first.await { it.phase == ModelPhase.ERROR }
                assertTrue(first.state.value.canRetry); assertEquals(old.sha256, first.state.value.installed?.sha256)
                server.bytes = good
                val restored = ModelManager(context, scope)
                assertEquals(selected, restored.state.value.target)
                withContext(Dispatchers.Main) { restored.retry() }
                assertEquals(selected, restored.state.value.target)
                restored.await { !it.busy && it.installed?.sha256 == selected.sha256 }
                assertTrue(File(old.path).exists()); assertEquals(2, restored.state.value.models.size)
                // Removing an inactive entry never changes the active model or deletes it.
                withContext(Dispatchers.Main) { restored.delete(old.sha256) }
                restored.await { !it.busy && it.message == "模型已删除" }
                assertEquals(selected.sha256, restored.state.value.installed?.sha256)
                assertEquals(1, restored.state.value.models.size)
            }
        } finally { scope.cancel(); context.cleanup() }
    }

    @Test fun cancelledReplacementRetainsEverySavedModel(): Unit = runBlocking {
        val context = IsolatedContext(base); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        try {
            val store = ModelFiles(File(context.filesDir, "models"))
            val a = store.install(("GGUF".toByteArray() + ByteArray(32)).inputStream())
            Settings(context).installModel(a.path, a.sha256, "first")
            val manager = ModelManager(context, scope)
            val b = File(context.filesDir, "second.gguf").apply { writeBytes("GGUF".toByteArray() + ByteArray(33)) }
            withContext(Dispatchers.Main) { manager.import(Uri.fromFile(b)) }
            manager.await { !it.busy && it.installed?.sha256 != a.sha256 }
            val hashes = manager.state.value.models.map { it.model.sha256 }.toSet()
            val bytes = payload
            ModelServer(bytes, 40).use { server ->
                withContext(Dispatchers.Main) { manager.download(spec(server, bytes)) }
                manager.await { it.completed > 0 && it.phase == ModelPhase.DOWNLOADING }
                withContext(Dispatchers.Main) { manager.cancel() }
                manager.await { !it.busy && it.message == "已取消" }
                assertEquals(hashes, manager.state.value.models.map { it.model.sha256 }.toSet())
                assertEquals(2, File(context.filesDir, "models").listFiles()!!.size)
                assertTrue(b.exists())
            }
        } finally { scope.cancel(); context.cleanup() }
    }

}
