package org.pararead.core

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class HfRepository(val id: String, val downloads: Long)
data class HfModelFile(val filename: String, val asset: ModelAsset)
data class HfModelDetails(val repository: String, val revision: String, val license: String, val files: List<HfModelFile>)

/** Public GGUF files only. Every download is pinned to a commit and an advertised LFS SHA-256. */
class HuggingFaceCatalog(private val origin: HttpUrl = "https://huggingface.co/".toHttpUrl(),
    private val client: OkHttpClient = OkHttpClient.Builder().callTimeout(45, TimeUnit.SECONDS).build()) {
    suspend fun search(query: String): List<HfRepository> {
        require(query.length <= 120) { "搜索词过长" }
        val url = origin.newBuilder().addPathSegments("api/models").addQueryParameter("search", query.trim())
            .addQueryParameter("filter", "gguf").addQueryParameter("sort", "downloads")
            .addQueryParameter("direction", "-1").addQueryParameter("limit", "20").build()
        val rows = JSONArray(get(url))
        return (0 until rows.length()).mapNotNull { i ->
            val row = rows.getJSONObject(i); val id = row.optString("id")
            if (validRepository(id)) HfRepository(id, row.optLong("downloads")) else null
        }
    }
    suspend fun details(repository: String): HfModelDetails {
        require(validRepository(repository)) { "无效的 Hugging Face 仓库名称" }
        val url = origin.newBuilder().addPathSegments("api/models").apply { repository.split('/').forEach(::addPathSegment) }
            .addQueryParameter("blobs", "true").build()
        val j = JSONObject(get(url))
        require(!j.optBoolean("private") && (j.opt("gated") == false || j.isNull("gated"))) { "暂只支持公开且无需授权的模型" }
        val revision = j.getString("sha"); require(revision.matches(Regex("[a-f0-9]{40}"))) { "仓库版本信息无效" }
        val card = j.optJSONObject("cardData")
        val license = card?.optString("license_name")?.takeIf { it.isNotBlank() }
            ?: card?.optString("license")?.takeIf { it.isNotBlank() } ?: "未声明，请查看模型卡"
        val rows = j.optJSONArray("siblings") ?: JSONArray()
        val files = (0 until rows.length()).mapNotNull { i ->
            val row = rows.getJSONObject(i); val name = row.optString("rfilename")
            val lfs = row.optJSONObject("lfs") ?: return@mapNotNull null
            val hash = lfs.optString("sha256"); val bytes = lfs.optLong("size", row.optLong("size"))
            if (!safeGguf(name) || !hash.matches(Regex("[a-f0-9]{64}")) || bytes <= 24) return@mapNotNull null
            val download = origin.newBuilder().apply { repository.split('/').forEach(::addPathSegment) }
                .addPathSegment("resolve").addPathSegment(revision).apply { name.split('/').forEach(::addPathSegment) }
                .addQueryParameter("download", "true").build().toString()
            HfModelFile(name, ModelAsset("$repository · ${name.substringAfterLast('/')}", download, bytes, hash, license, repository, revision))
        }.sortedWith(compareBy<HfModelFile> { !it.filename.contains("Q4_K_M", true) }.thenBy { it.asset.bytes })
        return HfModelDetails(repository, revision, license, files)
    }
    private suspend fun get(url: HttpUrl): String = suspendCancellableCoroutine { continuation ->
        val call = client.newCall(Request.Builder().url(url).header("Accept", "application/json").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) { if (continuation.isActive) continuation.resumeWithException(e) }
            override fun onResponse(call: Call, response: Response) {
                val value = runCatching { response.use {
                    check(it.isSuccessful) { if (it.code in setOf(401, 403)) "模型需要访问授权，暂不支持" else "Hugging Face 请求失败（${it.code}）" }
                    val body = checkNotNull(it.body); val out = java.io.ByteArrayOutputStream()
                    body.byteStream().use { input ->
                        val bytes = ByteArray(8192)
                        while (true) { val count = input.read(bytes); if (count < 0) break; check(out.size() + count <= 4 * 1048576) { "模型列表过大" }; out.write(bytes, 0, count) }
                    }
                    out.toString(Charsets.UTF_8.name())
                } }
                if (continuation.isActive) value.fold(continuation::resume, continuation::resumeWithException)
            }
        })
    }
    companion object {
        fun validRepository(id: String) = id.matches(Regex("[A-Za-z0-9][A-Za-z0-9._-]*/[A-Za-z0-9][A-Za-z0-9._-]*"))
        fun safeGguf(name: String): Boolean = name.endsWith(".gguf", true) && name.length <= 240 &&
            name.split('/').all { it.isNotBlank() && it !in setOf(".", "..") && !it.contains('\\') && it.none(Char::isISOControl) } &&
            !name.contains("mmproj", true) && !Regex("-\\d{5}-of-\\d{5}").containsMatchIn(name)
    }
}
