package org.pararead.core

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class ModelDownloadTest {
    private val payload = "GGUF".toByteArray() + ByteArray(262144) { (it % 127).toByte() }
    private fun asset(server: MockWebServer) = ModelAsset("fixture", server.url("/model.gguf").toString(), payload.size.toLong(),
        MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) })
    private fun response(bytes: ByteArray = payload) = MockResponse().setBody(Buffer().write(bytes))
    private fun range(offset: Int) = response(payload.copyOfRange(offset, payload.size)).setResponseCode(206)
        .setHeader("Content-Range", "bytes $offset-${payload.size - 1}/${payload.size}")
    private fun fixture(block: suspend (MockWebServer, File, ModelDownload) -> Unit): Unit = runBlocking {
        val directory = Files.createTempDirectory("model-download").toFile()
        val client = OkHttpClient.Builder().readTimeout(5, TimeUnit.SECONDS).build()
        try { MockWebServer().use { server -> server.start(); block(server, directory, ModelDownload(client)) } }
        finally { client.dispatcher.executorService.shutdown(); client.connectionPool.evictAll(); directory.deleteRecursively() }
    }
    @Test fun resumesAndPublishesTheSameFileWithoutCopying() = fixture { server, directory, download ->
        val part = File(directory, "download.part").apply { writeBytes(payload.copyOf(40000)) }
        val identity = Files.getAttribute(part.toPath(), "basic:fileKey")
        server.enqueue(range(40000))
        download.fetch(asset(server), part)
        assertEquals("bytes=40000-", server.takeRequest().getHeader("Range"))
        val ready = ModelFiles(directory).verifyAndPublish(part, asset(server))
        assertArrayEquals(payload, File(ready.path).readBytes())
        assertEquals(identity, Files.getAttribute(File(ready.path).toPath(), "basic:fileKey"))
        assertFalse(part.exists()); assertEquals(1, directory.listFiles()!!.size)
    }
    @Test fun ignoredRangeRestartsInsteadOfAppending() = fixture { server, directory, download ->
        val part = File(directory, "download.part").apply { writeBytes(payload.copyOf(40000)) }
        server.enqueue(response())
        download.fetch(asset(server), part)
        assertArrayEquals(payload, part.readBytes())
    }
    @Test fun wrongRangeIsRejectedWithoutPublishing() = fixture { server, directory, download ->
        val part = File(directory, "download.part").apply { writeBytes(payload.copyOf(40000)) }
        server.enqueue(range(0))
        try { download.fetch(asset(server), part); fail("Expected a range mismatch") } catch (_: IllegalArgumentException) { }
        assertEquals(40000, part.length().toInt()); assertFalse(directory.listFiles()!!.any { it.extension == "gguf" })
    }
    @Test fun disconnectedBodyKeepsBytesForTheNextRequest() = fixture { server, directory, download ->
        val part = File(directory, "download.part")
        server.enqueue(response().setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
        try { download.fetch(asset(server), part); fail("Expected a broken connection") } catch (_: IOException) { }
        val offset = part.length().toInt()
        assertTrue(offset in 1 until payload.size)
        server.takeRequest()
        server.enqueue(range(offset))
        download.fetch(asset(server), part)
        assertEquals("bytes=$offset-", server.takeRequest().getHeader("Range"))
        ModelFiles(directory).verifyAndPublish(part, asset(server))
    }
    @Test fun corruptModelNeverReplacesAnInstalledFile() = fixture { server, directory, download ->
        val old = ModelFiles(directory).install(("GGUF".toByteArray() + ByteArray(32)).inputStream())
        val part = File(directory, "download.part")
        server.enqueue(response(payload.clone().apply { this[100] = 99 }))
        download.fetch(asset(server), part)
        try { ModelFiles(directory).verifyAndPublish(part, asset(server)); fail("Expected a bad SHA") } catch (_: IllegalArgumentException) { }
        assertTrue(File(old.path).exists()); assertEquals(1, directory.listFiles()!!.count { it.extension == "gguf" })
    }
    @Test fun cancellationInterruptsABlockedBodyRead() = fixture { server, directory, download ->
        server.enqueue(response().throttleBody(1024, 1, TimeUnit.SECONDS))
        val part = File(directory, "download.part")
        coroutineScope {
            val running = launch { download.fetch(asset(server), part) }
            withTimeout(5000) { while (part.length() == 0L) delay(10) }
            withTimeout(2000) { running.cancelAndJoin() }
        }
        assertFalse(directory.listFiles()!!.any { it.extension == "gguf" })
    }
    @Test fun oversizedResponseIsRejected() = fixture { server, directory, download ->
        server.enqueue(response(payload + byteArrayOf(1)))
        val part = File(directory, "download.part")
        try { download.fetch(asset(server), part); fail("Expected oversize rejection") } catch (_: IllegalArgumentException) { }
        assertTrue(part.length() <= payload.size)
    }
    @Test fun completedPartialNeedsOnlyVerification() = fixture { server, directory, download ->
        val part = File(directory, "download.part").apply { writeBytes(payload) }
        download.fetch(asset(server), part)
        ModelFiles(directory).verifyAndPublish(part, asset(server))
        assertEquals(0, server.requestCount)
    }
}
