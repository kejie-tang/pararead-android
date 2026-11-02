package org.pararead.core

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.Test
import kotlin.test.*

class OllamaThinkingTest {
    @Test fun explicitOllamaModeDoesNotLeakIntoOtherServersWithQwenAliases() = runBlocking {
        val server = MockWebServer(); server.start()
        try {
            for (model in listOf("qwen3:0.6b", "qwen3:1.7b", "qwen3.5:0.8b", "generic-model")) {
                server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"译文\"}}]}"))
                OpenAICompatibleProvider(server.url("/v1").toString(), model)
                    .generate(ModelPrompt("system", "user\n/no_think", outputFormat = ModelOutputFormat.TEXT)).toList()
                val body = JSONObject(server.takeRequest().body.readUtf8())
                assertFalse(body.getJSONObject("chat_template_kwargs").getBoolean("enable_thinking"))
                assertFalse(body.has("reasoning_effort"), "A Qwen alias does not identify an Ollama server")
            }
            server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":\"译文\"}}]}"))
            OpenAICompatibleProvider(server.url("/v1").toString(), "custom-alias", ollamaThinkingOff = true)
                .generate(ModelPrompt("system", "user", outputFormat = ModelOutputFormat.TEXT)).toList()
            assertEquals("none", JSONObject(server.takeRequest().body.readUtf8()).getString("reasoning_effort"))
            assertTrue(OpenAICompatibleProvider.isOllamaEndpoint("http://localhost:11434/v1"))
            assertFalse(OpenAICompatibleProvider.isOllamaEndpoint("https://api.example.org/v1"))
        } finally { server.shutdown() }
    }
}
