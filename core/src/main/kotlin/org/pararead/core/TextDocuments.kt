package org.pararead.core

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.InterruptedIOException
import java.io.OutputStreamWriter
import java.io.PushbackReader
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Collections
import java.util.Locale

/** Byte ranges refer only to canonical UTF-8, never to the original input encoding. */
data class TextPageRange(val byteOffset: Long, val byteLength: Int)
data class TextChapter(val title: String, val page: Int)

class TextDocumentIndex internal constructor(
    val version: Int,
    /** Original decoding charset; UTF-8 when rebuilt without the original sidecar. */
    val encoding: String,
    pages: List<TextPageRange>,
    chapters: List<TextChapter>,
    internal val fileBytes: Long,
    internal val fileSha256: String,
    pageHashes: List<String>,
) {
    val pages: List<TextPageRange> = Collections.unmodifiableList(pages.toList())
    val chapters: List<TextChapter> = Collections.unmodifiableList(chapters.toList())
    internal val pageHashes: List<String> = Collections.unmodifiableList(pageHashes.toList())
}

/**
 * Blocking, Android-independent TXT IO; call off the UI thread. Page and chapter numbers are 1-based.
 * Input is limited to 64 MiB, canonical UTF-8 to 192 MiB, and indexes to 100,000 pages/10,000 chapters.
 * Thread interruption aborts IO with InterruptedIOException without clearing the interrupt flag.
 * Only LF/tab are accepted control characters. Pagination is independent of font and screen size.
 * Every open verifies the content, not just its timestamp; readPage verifies just the requested page.
 * Publication uses sibling temporary files and atomic rename. A failed sidecar publication can be
 * recovered by open; callers must not concurrently replace the same target.
 */
object TextDocuments {
    const val VERSION = 1
    const val PAGE_UTF16_UNITS = 4000
    const val MAX_INPUT_BYTES = 64L * 1024 * 1024
    private const val MAX_CANONICAL_BYTES = MAX_INPUT_BYTES * 3
    private const val MAX_PAGES = 100_000
    private const val MAX_CHAPTERS = 10_000
    private const val MAX_INDEX_BYTES = 32L * 1024 * 1024
    private val encodings = setOf("UTF-8", "UTF-16LE", "UTF-16BE", "GB18030", "GBK")
    private val chineseChapter = Regex("^第[ \\t]*[〇零一二三四五六七八九十百千万两0-9０-９壹贰叁肆伍陆柒捌玖拾佰仟]+[ \\t]*[章卷节][^。！？!?；;…]{0,60}$")
    private val specialChapter = Regex("^(?:序章|序言|楔子|尾声|后记)(?:[ \\t　:：—-]+[^。！？!?；;…]{1,60})?$")
    private val englishChapter = Regex("^chapter[ \\t]+(?:[0-9]+|[ivxlcdm]+)(?:[ \\t:：.—-]+[^.!?。！？;；]{1,60})?$", RegexOption.IGNORE_CASE)

    /** Does not delete or modify input. Explicit encodings must agree with a recognized BOM. */
    fun importFile(input: File, target: File, encoding: String? = null): TextDocumentIndex {
        checkInterrupted()
        require(input.canonicalFile != target.canonicalFile) { "TXT input and target must be different files" }
        require(input.isFile && input.length() in 1..MAX_INPUT_BYTES) { "TXT input must be nonempty and at most 64 MiB" }
        val explicit = encoding?.let { allowedEncoding(it) }
        val bom = detectBom(input)
        require(explicit == null || bom == null || explicit == bom.first) { "TXT encoding conflicts with BOM" }
        val parent = target.absoluteFile.parentFile
        check(parent.mkdirs() || parent.isDirectory) { "Cannot create TXT directory" }
        val temp = File.createTempFile(".txt-import-", ".tmp", parent)
        try {
            var selected = explicit ?: bom?.first ?: "UTF-8"
            try {
                transcode(input, temp, selected, bom?.second ?: 0)
            } catch (invalid: CharacterCodingException) {
                if (explicit != null || bom != null) throw invalid
                selected = "GB18030"
                transcode(input, temp, selected, 0)
            }
            val index = buildIndex(temp, selected)
            atomicReplace(temp, target)
            writeIndex(target, index)
            return index
        } finally { temp.delete() }
    }

    /** Loads a verified sidecar, or rebuilds it from an already-canonical UTF-8 file. */
    fun open(file: File): TextDocumentIndex {
        checkInterrupted()
        require(file.isFile && file.length() in 1..MAX_CANONICAL_BYTES) { "Invalid canonical TXT size" }
        val cached = try { loadIndex(file) } catch (interrupted: InterruptedIOException) {
            throw interrupted
        } catch (_: Exception) { null }
        if (cached != null) return cached
        return buildIndex(file, "UTF-8").also { writeIndex(file, it) }
    }

    fun readPage(file: File, index: TextDocumentIndex, page: Int): String {
        checkInterrupted()
        require(index.version == VERSION && page in 1..index.pages.size) { "Invalid TXT page" }
        require(file.length() == index.fileBytes) { "TXT changed; reopen the document" }
        val range = index.pages[page - 1]
        validateRange(range, index.fileBytes)
        val bytes = ByteArray(range.byteLength)
        RandomAccessFile(file, "r").use { it.seek(range.byteOffset); it.readFully(bytes) }
        require(hash(bytes) == index.pageHashes[page - 1]) { "TXT changed; reopen the document" }
        return decodePage(bytes)
    }

    private fun allowedEncoding(value: String): String = value.trim().uppercase(Locale.ROOT).also {
        require(it in encodings) { "Unsupported TXT encoding: $value" }
    }

    private fun detectBom(file: File): Pair<String, Int>? = file.inputStream().use {
        val a = it.read(); val b = it.read(); val c = it.read()
        when {
            a == 0xef && b == 0xbb && c == 0xbf -> "UTF-8" to 3
            a == 0xff && b == 0xfe -> "UTF-16LE" to 2
            a == 0xfe && b == 0xff -> "UTF-16BE" to 2
            else -> null
        }
    }

    private fun decoder(encoding: String) = Charset.forName(encoding).newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)

    private fun transcode(input: File, output: File, encoding: String, skip: Int) {
        var hasText = false
        LimitedInput(input.inputStream(), MAX_INPUT_BYTES).buffered().use { source ->
            repeat(skip) { require(source.read() >= 0) { "Truncated TXT BOM" } }
            InputStreamReader(source, decoder(encoding)).use { reader ->
                FileOutputStream(output).use { stream ->
                    val encoder = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                    val writer = OutputStreamWriter(stream, encoder).buffered()
                    val buffer = CharArray(8192)
                    var previousCr = false
                    while (true) {
                        checkInterrupted()
                        val count = reader.read(buffer)
                        if (count < 0) break
                        for (i in 0 until count) {
                            val ch = buffer[i]
                            if (ch == '\n' && previousCr) { previousCr = false; continue }
                            previousCr = ch == '\r'
                            val normalized = if (previousCr) '\n' else ch
                            validateCharacter(normalized)
                            if (!isBlank(normalized)) hasText = true
                            writer.write(normalized.code)
                        }
                    }
                    require(hasText) { "TXT contains no readable text" }
                    writer.flush()
                    require(stream.channel.size() <= MAX_CANONICAL_BYTES) { "Canonical TXT is too large" }
                    stream.fd.sync()
                }
            }
        }
    }

    private fun buildIndex(file: File, encoding: String): TextDocumentIndex {
        require(file.length() in 1..MAX_CANONICAL_BYTES) { "Invalid canonical TXT size" }
        val pages = mutableListOf<TextPageRange>()
        val chapters = mutableListOf<TextChapter>()
        val hashes = mutableListOf<String>()
        val digest = MessageDigest.getInstance("SHA-256")
        val page = StringBuilder(PAGE_UTF16_UNITS)
        var offset = 0L
        var hasText = false
        fun flushPage() {
            if (page.isEmpty()) return
            require(pages.size < MAX_PAGES) { "TXT has too many pages (maximum $MAX_PAGES)" }
            val bytes = page.toString().toByteArray(Charsets.UTF_8)
            pages.add(TextPageRange(offset, bytes.size)); hashes.add(hash(bytes))
            digest.update(bytes); offset += bytes.size
            page.setLength(0)
        }
        LimitedInput(file.inputStream(), MAX_CANONICAL_BYTES).use { source ->
            PushbackReader(InputStreamReader(source, decoder("UTF-8")).buffered(), 2).use { reader ->
                var lineStart = true
                while (true) {
                    val segment = readSegment(reader) ?: break
                    require(offset != 0L || page.isNotEmpty() || segment.text.first() != '\uFEFF') { "Canonical TXT must not have a BOM" }
                    segment.text.forEach { ch ->
                        validateCharacter(ch)
                        require(ch != '\r') { "Canonical TXT must use LF, not CR" }
                        if (!isBlank(ch)) hasText = true
                    }
                    val title = if (lineStart && segment.lineEnd) chapterTitle(segment.text) else null
                    if (title != null) {
                        flushPage()
                        require(chapters.size < MAX_CHAPTERS) { "TXT has too many chapters (maximum $MAX_CHAPTERS)" }
                        chapters.add(TextChapter(title, pages.size + 1))
                    }
                    if (page.length + segment.text.length > PAGE_UTF16_UNITS) flushPage()
                    page.append(segment.text)
                    lineStart = segment.lineEnd
                }
            }
        }
        flushPage()
        require(hasText && pages.isNotEmpty()) { "TXT contains no readable text" }
        require(offset == file.length()) { "Canonical TXT changed while indexing" }
        return TextDocumentIndex(VERSION, encoding, pages, chapters, offset, hex(digest.digest()), hashes)
    }

    private data class Segment(val text: String, val lineEnd: Boolean)

    /** A physical line can be arbitrarily long: never buffer more than one page. */
    private fun readSegment(reader: PushbackReader): Segment? {
        checkInterrupted()
        val text = StringBuilder(PAGE_UTF16_UNITS)
        while (text.length < PAGE_UTF16_UNITS) {
            val value = reader.read()
            if (value < 0) return if (text.isEmpty()) null else Segment(text.toString(), true)
            val ch = value.toChar()
            if (ch.isHighSurrogate()) {
                val low = reader.read()
                require(low >= 0 && low.toChar().isLowSurrogate()) { "Invalid TXT surrogate" }
                if (text.length + 2 > PAGE_UTF16_UNITS) {
                    reader.unread(low); reader.unread(value)
                    break
                }
                text.append(ch).append(low.toChar())
            } else {
                require(!ch.isLowSurrogate()) { "Invalid TXT surrogate" }
                text.append(ch)
                if (ch == '\n') return Segment(text.toString(), true)
            }
        }
        return Segment(text.toString(), false)
    }

    private fun chapterTitle(line: String): String? {
        if (line.length > 82) return null
        val title = line.trim()
        if (title.length !in 2..80) return null
        return title.takeIf { chineseChapter.matches(it) || specialChapter.matches(it) || englishChapter.matches(it) }
    }

    private fun validateCharacter(ch: Char) {
        require(!Character.isISOControl(ch) || ch == '\n' || ch == '\t') { "TXT contains binary/control data" }
    }
    private fun isBlank(ch: Char) = ch.isWhitespace() || Character.isSpaceChar(ch)

    private fun decodePage(bytes: ByteArray): String {
        val text = decoder("UTF-8").decode(ByteBuffer.wrap(bytes)).toString()
        require(text.length in 1..PAGE_UTF16_UNITS && '\r' !in text) { "Invalid TXT page content" }
        text.forEach { validateCharacter(it) }
        return text
    }

    private fun validateRange(range: TextPageRange, size: Long) {
        require(range.byteOffset >= 0 && range.byteLength in 1..PAGE_UTF16_UNITS * 3 &&
            range.byteOffset <= size - range.byteLength) { "Invalid TXT page range" }
    }

    private fun sidecar(file: File) = File(file.path + ".index.json")

    private fun indexJson(index: TextDocumentIndex): JSONObject = JSONObject()
        .put("version", index.version).put("encoding", index.encoding)
        .put("fileBytes", index.fileBytes).put("fileSha256", index.fileSha256)
        .put("pages", JSONArray().also { array -> index.pages.forEachIndexed { i, p ->
            checkInterrupted()
            array.put(JSONObject().put("byteOffset", p.byteOffset).put("byteLength", p.byteLength)
                .put("sha256", index.pageHashes[i]))
        } })
        .put("chapters", JSONArray().also { array -> index.chapters.forEach {
            checkInterrupted()
            array.put(JSONObject().put("title", it.title).put("page", it.page))
        } })
        .put("indexSha256", indexHash(index))

    /** Independent of JSONObject's platform-specific key ordering. */
    private fun indexHash(index: TextDocumentIndex): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(value: Any) {
            checkInterrupted()
            digest.update(value.toString().toByteArray(Charsets.UTF_8)); digest.update(0.toByte())
        }
        field(index.version); field(index.encoding); field(index.fileBytes); field(index.fileSha256)
        field(index.pages.size)
        index.pages.forEachIndexed { i, p -> field(p.byteOffset); field(p.byteLength); field(index.pageHashes[i]) }
        field(index.chapters.size)
        index.chapters.forEach { field(it.title); field(it.page) }
        return hex(digest.digest())
    }

    private fun writeIndex(file: File, index: TextDocumentIndex) {
        val target = sidecar(file).absoluteFile
        val temp = File.createTempFile(".txt-index-", ".tmp", target.parentFile)
        try {
            val bytes = indexJson(index).toString().toByteArray(Charsets.UTF_8)
            require(bytes.size <= MAX_INDEX_BYTES) { "TXT index is too large" }
            FileOutputStream(temp).use { it.write(bytes); it.fd.sync() }
            atomicReplace(temp, target)
        } finally { temp.delete() }
    }

    private fun loadIndex(file: File): TextDocumentIndex {
        val sidecar = sidecar(file)
        require(sidecar.isFile && sidecar.length() in 1..MAX_INDEX_BYTES)
        val raw = LimitedInput(sidecar.inputStream(), MAX_INDEX_BYTES).use {
            InputStreamReader(it, decoder("UTF-8")).readText()
        }
        val json = JSONObject(raw)
        checkInterrupted()
        require(integer(json, "version") == VERSION.toLong())
        val encoding = allowedEncoding(json.getString("encoding"))
        val size = integer(json, "fileBytes")
        require(size == file.length() && size in 1..MAX_CANONICAL_BYTES)
        val fileHash = json.getString("fileSha256")
        require(fileHash.matches(Regex("[a-f0-9]{64}")))
        val pageJson = json.getJSONArray("pages")
        val chapterJson = json.getJSONArray("chapters")
        require(pageJson.length() in 1..MAX_PAGES) { "Invalid TXT page count (maximum $MAX_PAGES)" }
        require(chapterJson.length() <= MAX_CHAPTERS) { "TXT has too many chapters (maximum $MAX_CHAPTERS)" }
        val pages = ArrayList<TextPageRange>(pageJson.length())
        val hashes = ArrayList<String>(pageJson.length())
        var offset = 0L
        for (i in 0 until pageJson.length()) {
            checkInterrupted()
            val p = pageJson.getJSONObject(i)
            val length = integer(p, "byteLength")
            require(length in 1..PAGE_UTF16_UNITS * 3L)
            val range = TextPageRange(integer(p, "byteOffset"), length.toInt())
            validateRange(range, size)
            require(range.byteOffset == offset)
            offset += range.byteLength
            pages.add(range)
            hashes.add(p.getString("sha256").also { require(it.matches(Regex("[a-f0-9]{64}"))) })
        }
        require(offset == size)
        val chapters = ArrayList<TextChapter>(chapterJson.length())
        var previousPage = 0
        for (i in 0 until chapterJson.length()) {
            checkInterrupted()
            val c = chapterJson.getJSONObject(i)
            val title = c.getString("title")
            val page = integer(c, "page")
            require(page in (previousPage + 1).toLong()..pages.size.toLong() && chapterTitle(title) == title)
            chapters.add(TextChapter(title, page.toInt())); previousPage = page.toInt()
        }
        val index = TextDocumentIndex(VERSION, encoding, pages, chapters, size, fileHash, hashes)
        require(json.getString("indexSha256") == indexHash(index))
        // Validate every boundary by strict decoding, and all bytes by digest, before exposing offsets.
        val digest = MessageDigest.getInstance("SHA-256")
        var hasText = false
        var chapterCursor = 0
        RandomAccessFile(file, "r").use { input ->
            pages.forEachIndexed { i, range ->
                checkInterrupted()
                val bytes = ByteArray(range.byteLength)
                input.readFully(bytes)
                require(hash(bytes) == hashes[i])
                digest.update(bytes)
                val text = decodePage(bytes)
                require(i != 0 || text.first() != '\uFEFF')
                if (text.any { !isBlank(it) }) hasText = true
                if (chapterCursor < chapters.size && chapters[chapterCursor].page == i + 1) {
                    require(chapterTitle(text.substringBefore('\n')) == chapters[chapterCursor].title)
                    chapterCursor++
                }
            }
            require(input.read() == -1)
        }
        require(hasText && hex(digest.digest()) == fileHash)
        return index
    }

    private fun integer(json: JSONObject, key: String): Long {
        val value = json.get(key)
        require(value is Int || value is Long) { "Invalid TXT index integer: $key" }
        return (value as Number).toLong()
    }

    private fun checkInterrupted() {
        if (Thread.currentThread().isInterrupted) throw InterruptedIOException("TXT IO interrupted")
    }

    private fun atomicReplace(source: File, target: File) {
        checkInterrupted()
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    }
    private fun hash(bytes: ByteArray) = hex(MessageDigest.getInstance("SHA-256").digest(bytes))
    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    private class LimitedInput(private val source: InputStream, private val limit: Long) : InputStream() {
        private var count = 0L
        private fun counted(n: Int): Int {
            if (n > 0) count += n
            require(count <= limit) { "TXT byte limit exceeded ($limit)" }
            return n
        }
        override fun read(): Int {
            checkInterrupted()
            return source.read().also { if (it >= 0) counted(1) }
        }
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            checkInterrupted()
            return counted(source.read(buffer, offset, length))
        }
        override fun close() = source.close()
    }
}
