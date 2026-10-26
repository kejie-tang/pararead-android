package org.pararead.core

import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext

data class ModelAsset(val name: String, val url: String, val bytes: Long, val sha256: String)
data class InstalledModel(val path: String, val sha256: String, val bytes: Long)

object DefaultModel {
    // Ollama library/qwen3:1.7b model layer, pinned by content digest, Q4_K_M / Apache-2.0.
    val asset = ModelAsset("qwen3:1.7b",
        "https://registry.ollama.ai/v2/library/qwen3/blobs/sha256:3d0b790534fe4b79525fc3692950408dca41171676ed7e21db57af5c65ef6ab6",
        1359279776L, "3d0b790534fe4b79525fc3692950408dca41171676ed7e21db57af5c65ef6ab6")
}

/** Publish complete, verified files by rename. A failed transfer never replaces an installed model. */
class ModelFiles(val directory: File) {
    init { check(directory.mkdirs() || directory.isDirectory) }
    suspend fun install(input: InputStream, expected: ModelAsset? = null, progress: (Long) -> Unit = {}): InstalledModel {
        val temp = File(directory, "${UUID.randomUUID()}.part")
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        try {
            FileOutputStream(temp).use { out ->
                val header = ByteArray(4); var offset = 0
                while (offset < header.size) {
                    coroutineContext.ensureActive()
                    val n = input.read(header, offset, header.size - offset)
                    require(n >= 0) { "模型不是 GGUF 文件" }; if (n > 0) offset += n
                }
                require(header.contentEquals("GGUF".toByteArray(Charsets.US_ASCII))) { "模型不是 GGUF 文件" }
                out.write(header); digest.update(header); total = 4
                val buffer = ByteArray(262144)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buffer); if (n < 0) break; if (n == 0) continue
                    total += n
                    require(expected == null || total <= expected.bytes) { "模型文件大小不匹配，请重试下载" }
                    out.write(buffer, 0, n); digest.update(buffer, 0, n); progress(total)
                }
                coroutineContext.ensureActive()
                require(total > 24 && (expected == null || total == expected.bytes)) { "模型文件不完整，请重试下载" }
                out.fd.sync()
            }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            require(expected == null || hash == expected.sha256) { "模型 SHA-256 校验失败，请重试下载" }
            coroutineContext.ensureActive()
            val target = File(directory, "$hash.gguf")
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            return InstalledModel(target.absolutePath, hash, total)
        } finally { temp.delete() }
    }
    fun removeExcept(keep: String? = null) {
        directory.listFiles()?.filter { it.isFile && (it.extension == "gguf" || it.extension == "part") && it.absolutePath != keep }?.forEach {
            check(it.delete() || !it.exists()) { "无法删除模型文件" }
        }
    }
}
