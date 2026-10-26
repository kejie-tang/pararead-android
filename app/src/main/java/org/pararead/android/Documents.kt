package org.pararead.android

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.*
import java.security.MessageDigest
import java.util.UUID
import kotlin.coroutines.coroutineContext

/** Immutable private snapshot: supports seekable SAF files, pipes, cloud providers and lost grants uniformly. */
class DocumentRepository(private val context: Context, private val dao: ReaderDao) {
    private val directory = File(context.filesDir, "documents").apply { mkdirs() }
    private fun prefix(input: InputStream, limit: Int): ByteArray {
        val bytes = ByteArray(limit); var offset = 0
        while (offset < limit) { val count = input.read(bytes, offset, limit - offset); if (count < 0) break; if (count > 0) offset += count }
        return bytes.copyOf(offset)
    }
    suspend fun import(uri: Uri, progress: (Long) -> Unit): DocumentRecord = withContext(Dispatchers.IO) {
        val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: "document.pdf"
        context.contentResolver.openInputStream(uri)?.use { input -> copy(input, uri.toString(), name, progress) }
            ?: error("无法读取文件，请重新授权")
    }
    suspend fun sample(): DocumentRecord = withContext(Dispatchers.IO) {
        context.assets.open("reader/sample.pdf").use { copy(it, "asset:sample", "ParaRead 示例文档.pdf", {}) }
    }
    suspend fun benchmark(name: String): DocumentRecord = withContext(Dispatchers.IO) {
        require(BuildConfig.DEBUG && name.matches(Regex("[A-Za-z0-9._-]+\\.pdf")))
        val file = File(File(context.filesDir, "benchmarks"), name)
        file.inputStream().use { copy(it, "benchmark:$name", name, {}) }
    }
    private suspend fun copy(input: InputStream, uri: String, name: String, progress: (Long) -> Unit): DocumentRecord {
        val temp = File(directory, "${UUID.randomUUID()}.part")
        val digest = MessageDigest.getInstance("SHA-256")
        var length = 0L
        try {
            FileOutputStream(temp).use { output ->
                val header = prefix(input, 1024)
                require(String(header, Charsets.ISO_8859_1).contains("%PDF-")) { "不是 PDF 文件" }
                output.write(header); digest.update(header); length = header.size.toLong()
                val buffer = ByteArray(262144)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buffer); if (n < 0) break
                    if (n == 0) continue
                    output.write(buffer, 0, n); digest.update(buffer, 0, n); length += n; progress(length)
                }
                output.fd.sync()
            }
            require(length > 8) { "PDF 文件为空" }
            val hash = digest.digest().joinToString("") { "%02x".format(it) }
            val target = File(directory, "$hash.pdf")
            if (target.exists()) temp.delete() else check(temp.renameTo(target))
            val existing = dao.documents().firstOrNull { it.id == hash }
            val document = DocumentRecord(hash, uri, name, target.absolutePath, "sha256:$hash", length)
            dao.document(document)
            return existing?.copy(opened = document.opened) ?: document
        } finally { temp.delete() }
    }
}

/** Absolute-position reads; streams never share a seek cursor. */
class RangeSource(val document: DocumentRecord, draft: DraftRecord?) : Closeable {
    private val base = RandomAccessFile(document.path, "r")
    private val tail = draft?.takeIf { it.baseRevision == document.revision && File(it.path).length() == it.size }?.let { RandomAccessFile(it.path, "r") }
    val baseLength = document.length
    val length = baseLength + (tail?.length() ?: 0)
    val token = UUID.randomUUID().toString()
    val revision = document.revision + (tail?.let { ":draft:${File(draft!!.path).name}" } ?: "")
    fun read(begin: Long, end: Long): ByteArray {
        require(begin >= 0 && end > begin && end <= length && end - begin <= 262144) { "INVALID_RANGE" }
        val output = ByteArray((end - begin).toInt()); var offset = 0; var pos = begin
        while (pos < end) {
            val source = if (pos < baseLength) base else requireNotNull(tail)
            val boundary = if (pos < baseLength) minOf(end, baseLength) else end
            val buffer = java.nio.ByteBuffer.wrap(output, offset, (boundary - pos).toInt())
            val filePosition = if (pos < baseLength) pos else pos - baseLength
            var consumed = 0L
            while (buffer.hasRemaining()) { val n = source.channel.read(buffer, filePosition + consumed); check(n > 0) { "TRUNCATED_FILE" }; consumed += n }
            offset += consumed.toInt(); pos += consumed
        }
        return output
    }
    fun copyTo(output: OutputStream, suffix: File? = null) {
        var offset = 0L
        while (offset < length) { val data = read(offset, minOf(length, offset + 262144)); output.write(data); offset += data.size }
        suffix?.inputStream()?.use { it.copyTo(output, 262144) }
    }
    override fun close() { base.close(); tail?.close() }
}
