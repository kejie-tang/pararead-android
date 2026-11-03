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
import org.pararead.core.TextDocuments
import kotlin.coroutines.coroutineContext

/** Immutable private snapshot: supports seekable SAF files, pipes, cloud providers and lost grants uniformly. */
class DocumentRepository(private val context: Context, private val dao: ReaderDao) {
    private val directory = File(context.filesDir, "documents").apply { mkdirs() }
    suspend fun rename(document: DocumentRecord, name: String) = withContext(Dispatchers.IO) {
        val title = name.trim()
        require(title.isNotEmpty() && title.length <= 200)
        dao.rename(document.id, title)
    }
    suspend fun delete(document: DocumentRecord) = withContext(Dispatchers.IO) {
        val draft = dao.draft(document.id)
        dao.deleteDocument(document.id, document.revision)
        // Never follow the original content URI: only the app-owned snapshot and draft are removed.
        File(document.path).takeIf { it.parentFile?.canonicalFile == directory.canonicalFile }?.let { snapshot ->
            snapshot.delete()
            if (document.isText) File(snapshot.path + ".index.json").delete()
        }
        draft?.let { File(it.path).takeIf { file -> file.parentFile?.canonicalFile == context.filesDir.canonicalFile }?.delete() }
        DocumentThumbnails.remove(context, document.id)
    }
    private fun prefix(input: InputStream, limit: Int): ByteArray {
        val bytes = ByteArray(limit); var offset = 0
        while (offset < limit) { val count = input.read(bytes, offset, limit - offset); if (count < 0) break; if (count > 0) offset += count }
        return bytes.copyOf(offset)
    }
    private fun displayName(uri: Uri): String = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
        if (it.moveToFirst()) it.getString(0) else null
    } ?: if (context.contentResolver.getType(uri) == "text/plain") "document.txt" else "document.pdf"
    suspend fun isTextImport(uri: Uri): Boolean = withContext(Dispatchers.IO) {
        displayName(uri).endsWith(".txt", true) || context.contentResolver.getType(uri) == "text/plain"
    }
    suspend fun import(uri: Uri, encoding: String? = null, progress: (Long) -> Unit): DocumentRecord = withContext(Dispatchers.IO) {
        val name = displayName(uri)
        val text = name.endsWith(".txt", true) || context.contentResolver.getType(uri) == "text/plain"
        context.contentResolver.openInputStream(uri)?.use { input -> copy(input, uri.toString(), name, progress, text, encoding) }
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
    private suspend fun copy(input: InputStream, uri: String, name: String, progress: (Long) -> Unit,
        text: Boolean = false, encoding: String? = null): DocumentRecord {
        val temp = File(directory, "${UUID.randomUUID()}.part")
        val decoded = File(directory, "${UUID.randomUUID()}.txt.part")
        val digest = MessageDigest.getInstance("SHA-256")
        var length = 0L
        try {
            FileOutputStream(temp).use { output ->
                val header = prefix(input, 1024)
                require(text || String(header, Charsets.ISO_8859_1).contains("%PDF-")) { "请选择 PDF 或 TXT 文件" }
                output.write(header); digest.update(header); length = header.size.toLong()
                val buffer = ByteArray(262144)
                while (true) {
                    coroutineContext.ensureActive()
                    val n = input.read(buffer); if (n < 0) break
                    if (n == 0) continue
                    if (text) require(length + n <= 64L * 1024 * 1024) { "TXT 文件不能超过 64 MiB" }
                    output.write(buffer, 0, n); digest.update(buffer, 0, n); length += n; progress(length)
                }
                output.fd.sync()
            }
            require(length > if (text) 0 else 8) { if (text) "TXT 文件为空" else "PDF 文件为空" }
            val rawHash = digest.digest().joinToString("") { "%02x".format(it) }
            // Explicit decoding is part of TXT identity: re-importing with another encoding must
            // never overwrite a snapshot still used by a reader or background worker.
            val hash = documentSnapshotId(rawHash, text, encoding)
            val target = File(directory, "$hash.${if (text) "txt" else "pdf"}")
            val revision: String
            if (text) {
                val index = if (target.exists()) TextDocuments.open(target) else {
                    val value = TextDocuments.importFile(temp, decoded, encoding)
                    coroutineContext.ensureActive()
                    check(decoded.renameTo(target)) { "无法保存 TXT 快照" }
                    check(File(decoded.path + ".index.json").renameTo(File(target.path + ".index.json"))) { "无法保存 TXT 索引" }
                    value
                }
                revision = "sha256:$hash:txt-v${index.version}"
            } else {
                if (target.exists()) temp.delete() else check(temp.renameTo(target))
                revision = "sha256:$hash"
            }
            val existing = dao.document(hash)
            val document = existing?.copy(uri = uri) ?: DocumentRecord(hash, uri, name, target.absolutePath, revision, target.length())
            dao.document(document)
            return document
        } finally { temp.delete(); decoded.delete(); File(decoded.path + ".index.json").delete() }
    }
}

/** Keep historical PDF IDs; TXT always has a separate format/decoding domain, including auto. */
internal fun documentSnapshotId(rawHash: String, text: Boolean, encoding: String? = null): String {
    if (!text) return rawHash
    val charset = encoding?.trim()?.uppercase(java.util.Locale.ROOT) ?: "auto"
    return MessageDigest.getInstance("SHA-256").digest("$rawHash:txt:$charset".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

/** Format follows the immutable app-owned snapshot, not the user-editable display name. */
val DocumentRecord.isText: Boolean get() = path.endsWith(".txt", ignoreCase = true)

/** Absolute-position reads; streams never share a seek cursor. */
class RangeSource(val document: DocumentRecord, draft: DraftRecord?) : Closeable {
    private val base = RandomAccessFile(document.path, "r")
    private val tail = draft?.takeIf { !document.isText && it.baseRevision == document.revision && File(it.path).length() == it.size }?.let { RandomAccessFile(it.path, "r") }
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
