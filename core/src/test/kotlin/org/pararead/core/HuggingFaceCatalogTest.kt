package org.pararead.core

import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import kotlin.test.*

class HuggingFaceCatalogTest {
    @Test fun downloadsPinRevisionHashAndExposeLicenseWhileExcludingAuxiliaryAndSplitFiles() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""[{"id":"LiquidAI/LFM2.5-350M-GGUF","downloads":42}]"""))
            val row = """{"size":100,"lfs":{"size":100,"sha256":"${"a".repeat(64)}"},"rfilename":%s}"""
            val names = listOf("good Q4_K_M.gguf", "mmproj.gguf", "foo-00001-of-00002.gguf", "../escape.gguf", "bad.gguf")
            val rows = names.map { row.format(org.json.JSONObject.quote(it)) }.toMutableList()
            rows[4] = """{"rfilename":"bad.gguf","size":100}"""
            server.enqueue(MockResponse().setBody("""{"sha":"${"b".repeat(40)}","gated":false,"cardData":{"license":"other","license_name":"lfm1.0"},"siblings":[${rows.joinToString(",") }]}"""))
            val api = HuggingFaceCatalog(server.url("/"))
            assertEquals(42L, api.search("LFM2.5").single().downloads)
            val search = server.takeRequest(); assertTrue(search.path!!.contains("filter=gguf"))
            val details = api.details("LiquidAI/LFM2.5-350M-GGUF")
            assertEquals("lfm1.0", details.license)
            val file = details.files.single(); assertEquals("a".repeat(64), file.asset.sha256)
            assertTrue(file.asset.url.contains("/resolve/${"b".repeat(40)}/good%20Q4_K_M.gguf"))
        }
    }
    @Test fun gatedAndMalformedRevisionNeverBecomeDownloadable() = runBlocking {
        MockWebServer().use { server ->
            val api = HuggingFaceCatalog(server.url("/"))
            server.enqueue(MockResponse().setBody("""{"sha":"${"b".repeat(40)}","gated":"auto"}"""))
            assertTrue(runCatching { api.details("org/model") }.isFailure)
            server.enqueue(MockResponse().setBody("""{"sha":"main","gated":false}"""))
            assertTrue(runCatching { api.details("org/model") }.isFailure)
            assertFalse(HuggingFaceCatalog.validRepository("org/../../model"))
        }
    }
    @Test fun cancellingSearchCancelsTheNetworkCall() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE))
            val task = launch { HuggingFaceCatalog(server.url("/")).search("Qwen") }
            withContext(Dispatchers.IO) { assertNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)) }
            withTimeout(3_000) { task.cancelAndJoin() }
        }
    }
}
