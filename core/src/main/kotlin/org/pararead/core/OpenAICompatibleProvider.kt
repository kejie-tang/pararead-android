package org.pararead.core

import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.channels.trySendBlocking
import kotlinx.coroutines.flow.callbackFlow
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class OpenAICompatibleProvider(
    private val baseUrl: String, private val model: String, private val apiKey: String = "",
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(120, TimeUnit.SECONDS).readTimeout(60, TimeUnit.SECONDS).build(),
) : TranslationProvider {
    override fun generate(prompt: ModelPrompt) = callbackFlow {
        val body = JSONObject().put("model", model).put("temperature", 0.2).put("max_tokens", 2048).put("stream", true)
            .put("chat_template_kwargs", JSONObject().put("enable_thinking", false))
            .put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", prompt.system))
                .put(JSONObject().put("role", "user").put("content", prompt.user)))
        if (prompt.outputFormat == ModelOutputFormat.TRANSLATION_JSON) body.put("response_format", JSONObject().put("type", "json_object"))
        val url = baseUrl.trimEnd('/') + "/chat/completions"
        val builder = Request.Builder().url(url).post(body.toString().toRequestBody("application/json".toMediaType()))
        require(url.startsWith("https://") || isLocalHttp(url)) { "HTTP_ONLY_FOR_LOCAL_SERVER" }
        if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
        val call = client.newCall(builder.build())
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { close(e) }
            override fun onResponse(call: Call, response: Response) {
                response.use {
                    try {
                        check(response.isSuccessful) { "HTTP_${response.code}" }
                        val source = requireNotNull(response.body).source()
                        val output = StringBuilder()
                        var finish: String? = null
                        var done = false
                        if (response.header("Content-Type")?.startsWith("text/event-stream") == true) {
                            val event = StringBuilder()
                            fun dispatch() {
                                val data = event.toString().trimEnd(); event.setLength(0)
                                if (data.isEmpty()) return
                                if (data == "[DONE]") { done = true; return }
                                val choices = JSONObject(data).optJSONArray("choices") ?: return
                                if (choices.length() == 0) return
                                val choice = choices.getJSONObject(0)
                                if (!choice.isNull("finish_reason")) finish = choice.getString("finish_reason")
                                val delta = choice.optJSONObject("delta")?.optString("content", "") ?: ""
                                output.append(delta)
                                check(output.length <= 131072) { "OUTPUT_TOO_LARGE" }
                                if (delta.isNotEmpty()) check(trySendBlocking(ProviderEvent.Delta(delta)).isSuccess) { "OUTPUT_CHANNEL_CLOSED" }
                            }
                            while (!source.exhausted() && !done) {
                                val line = source.readUtf8LineStrict(262144)
                                if (line.isEmpty()) dispatch()
                                else if (line.startsWith("data:")) { event.append(line.substring(5).trimStart()).append('\n'); check(event.length <= 262144) }
                            }
                            if (event.isNotEmpty()) dispatch()
                            check(done && finish == "stop") { "TRUNCATED_STREAM_${finish ?: "unknown"}" }
                        } else {
                            val collected = okio.Buffer()
                            while (!source.exhausted()) {
                                val n = source.read(collected, minOf(8192L, 262145 - collected.size))
                                if (n < 0) break
                                check(collected.size <= 262144) { "OUTPUT_TOO_LARGE" }
                            }
                            val bytes = collected.readByteArray()
                            val choice = JSONObject(String(bytes, Charsets.UTF_8)).getJSONArray("choices").getJSONObject(0)
                            check(choice.getString("finish_reason") == "stop") { "TRUNCATED_RESULT" }
                            output.append(choice.getJSONObject("message").getString("content"))
                        }
                        check(trySendBlocking(ProviderEvent.Complete(output.toString())).isSuccess) { "OUTPUT_CHANNEL_CLOSED" }
                        close()
                    } catch (e: Exception) { close(e) }
                }
            }
        })
        awaitClose { call.cancel() }
    }
    companion object {
        fun isLocalHttp(url: String): Boolean {
            val host = runCatching { java.net.URI(url).host }.getOrNull() ?: return false
            return url.startsWith("http://") && (host in setOf("localhost", "127.0.0.1", "10.0.2.2", "[::1]") ||
                host.matches(Regex("10\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}|192\\.168\\.\\d{1,3}\\.\\d{1,3}|172\\.(1[6-9]|2[0-9]|3[01])\\.\\d{1,3}\\.\\d{1,3}")))
        }
    }
}
