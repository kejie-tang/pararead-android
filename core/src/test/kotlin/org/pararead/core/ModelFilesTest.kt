package org.pararead.core

import kotlinx.coroutines.*
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.*
import org.junit.Test

class ModelFilesTest {
    private val payload = "GGUF".toByteArray() + ByteArray(524288) { (it % 127).toByte() }
    private fun asset(bytes: ByteArray = payload) = ModelAsset("qwen3:1.7b", "https://example.invalid/model.gguf", bytes.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
    @Test fun verifiedInstallIsPublishedAndDeletionIsScopedToModels() = runBlocking {
        val root = Files.createTempDirectory("models").toFile()
        try {
            val pdf = File(root, "paper.pdf").apply { writeText("keep document") }
            val cache = File(root, "translations.db").apply { writeText("keep translations") }
            val store = ModelFiles(File(root, "models")); val spec = asset()
            val model = store.install(ByteArrayInputStream(payload), spec)
            assertEquals(spec.sha256, model.sha256); assertContentEquals(payload, File(model.path).readBytes())
            assertEquals(listOf("${spec.sha256}.gguf"), store.directory.list()!!.toList())
            store.removeExcept(); assertTrue(store.directory.list()!!.isEmpty())
            assertEquals("keep document", pdf.readText()); assertEquals("keep translations", cache.readText())
        } finally { root.deleteRecursively() }
    }
    @Test fun truncationHashMismatchAndInvalidMagicKeepPreviousModel() = runBlocking {
        val root = Files.createTempDirectory("models").toFile()
        try {
            val store = ModelFiles(root); val previous = store.install(ByteArrayInputStream(payload))
            for ((bytes, spec) in listOf(payload.copyOf(100) to asset(), payload to asset().copy(sha256 = "0".repeat(64)),
                "<html>not a model</html>".toByteArray() to null, (payload + 1.toByte()) to asset())) {
                assertFailsWith<IllegalArgumentException> { store.install(ByteArrayInputStream(bytes), spec) }
                assertContentEquals(payload, File(previous.path).readBytes())
                assertEquals(1, root.list()!!.size)
            }
        } finally { root.deleteRecursively() }
    }
    @Test fun cancelledImportNeverPublishesPartialFile() = runBlocking {
        val root = Files.createTempDirectory("models").toFile()
        try {
            val store = ModelFiles(root)
            val job = launch(Dispatchers.Default) { store.install(ByteArrayInputStream(payload), asset()) { cancel() } }
            job.join(); assertTrue(job.isCancelled); assertTrue(root.list()!!.isEmpty())
        } finally { root.deleteRecursively() }
    }
    @Test fun defaultPinsTheRequestedOllamaTagAndThinkingIsDisabledBeforeGeneration() {
        assertEquals("qwen3:1.7b", TranslationConfig().model)
        assertEquals("qwen3:1.7b", DefaultModel.asset.name)
        val current = TranslationConfig(modelHash = DefaultModel.asset.sha256)
        assertEquals(contentKey("cached sentence", current.copy(model = "Qwen3-1.7B")), contentKey("cached sentence", current))
        assertNotEquals(current.copy(provider = "api").fingerprint(), current.copy(provider = "api", model = "Qwen3-1.7B").fingerprint())
        assertTrue(DefaultModel.asset.url.startsWith("https://registry.ollama.ai/"))
        assertTrue(DefaultModel.asset.url.endsWith(DefaultModel.asset.sha256))
        val prompt = Qwen3ChatTemplate.render(ModelPrompt("translate", "source text\n/no_think"))
        assertTrue(prompt.contains("source text\n/no_think<|im_end|>"))
        assertTrue(prompt.endsWith("<|im_start|>assistant\n<think>\n\n</think>\n\n"))
    }
}
