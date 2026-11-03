package org.pararead.core

import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.io.InterruptedIOException
import java.io.RandomAccessFile
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.*

class TextDocumentsTest {
    private fun inDirectory(test: (File) -> Unit) {
        val directory = Files.createTempDirectory("text-documents").toFile()
        try { test(directory) } finally { directory.deleteRecursively() }
    }

    private fun import(directory: File, bytes: ByteArray, encoding: String? = null): Pair<File, TextDocumentIndex> {
        val input = File(directory, "original.bin").apply { writeBytes(bytes) }
        val target = File(directory, "canonical.txt")
        val index = TextDocuments.importFile(input, target, encoding)
        assertContentEquals(bytes, input.readBytes(), "Import must preserve its input")
        assertTrue(File(target.path + ".index.json").isFile)
        return target to index
    }

    private fun joined(file: File, index: TextDocumentIndex) =
        (1..index.pages.size).joinToString("") { TextDocuments.readPage(file, index, it) }

    @Test fun utf8ImportNormalizesLineEndingsAndPreservesText() = inDirectory { root ->
        val original = "第一章 开始\r\n你好，世界😀\r第二行\n\r\n结束\r"
        val expected = "第一章 开始\n你好，世界😀\n第二行\n\n结束\n"
        val (file, index) = import(root, original.toByteArray())
        assertEquals("UTF-8", index.encoding)
        assertEquals(TextDocuments.VERSION, index.version)
        assertEquals(expected, file.readText())
        assertEquals(expected, joined(file, index))
        assertEquals(listOf(TextChapter("第一章 开始", 1)), index.chapters)
        assertEquals(0L, index.pages.first().byteOffset)
        assertEquals(file.length(), index.pages.sumOf { it.byteLength.toLong() })
    }

    @Test fun bomUtf8AndBothUtf16ByteOrdersAreStrictlyDecoded() = inDirectory { root ->
        val text = "序章\r\n中文与 emoji 😀\r最后一行"
        val cases = listOf(
            Triple("UTF-8", byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()), Charsets.UTF_8),
            Triple("UTF-16LE", byteArrayOf(0xff.toByte(), 0xfe.toByte()), Charsets.UTF_16LE),
            Triple("UTF-16BE", byteArrayOf(0xfe.toByte(), 0xff.toByte()), Charsets.UTF_16BE),
        )
        for ((name, bom, charset) in cases) {
            val (file, index) = import(root, bom + text.toByteArray(charset))
            assertEquals(name, index.encoding)
            assertEquals("序章\n中文与 emoji 😀\n最后一行", joined(file, index))
            assertFalse(file.readText().startsWith('\uFEFF'))
            assertEquals(name, TextDocuments.open(file).encoding)
        }
    }

    @Test fun gb18030FallbackAndExplicitGbkAndUtf16AreSupported() = inDirectory { root ->
        val text = "第一卷 中文\n简体繁體汉字与扩展字𠀀\n"
        val (file, index) = import(root, text.toByteArray(Charset.forName("GB18030")))
        assertEquals("GB18030", index.encoding)
        assertEquals(text, joined(file, index))
        for ((name, content) in listOf("GBK" to "中文书籍\r下一行", "UTF-16LE" to text, "UTF-16BE" to text)) {
            val (canonical, explicit) = import(root, content.toByteArray(Charset.forName(name)), name.lowercase())
            assertEquals(name, explicit.encoding)
            assertEquals(content.replace('\r', '\n'), joined(canonical, explicit))
        }
    }

    @Test fun explicitEncodingOverridesAmbiguousValidUtf8AndNeverSilentlyReplaces() = inDirectory { root ->
        // C2 A9 is valid both as UTF-8 (copyright sign) and as GBK (a Chinese character).
        val ambiguous = byteArrayOf(0xc2.toByte(), 0xa9.toByte())
        val (_, automatic) = import(root, ambiguous)
        assertEquals("UTF-8", automatic.encoding)
        val (file, explicit) = import(root, ambiguous, "GBK")
        assertEquals(String(ambiguous, Charset.forName("GBK")), joined(file, explicit))
        assertNotEquals("©", joined(file, explicit))

        val input = File(root, "bad.bin")
        for (name in listOf("UTF-8", "UTF-16LE", "UTF-16BE", "GB18030", "GBK")) {
            input.writeBytes(byteArrayOf(0x81.toByte()))
            assertFailsWith<CharacterCodingException>(name) {
                TextDocuments.importFile(input, File(root, "bad.txt"), name)
            }
        }
        input.writeBytes(byteArrayOf(0x81.toByte()))
        assertFailsWith<CharacterCodingException> { TextDocuments.importFile(input, File(root, "bad.txt")) }
        input.writeBytes(byteArrayOf(0xff.toByte(), 0xfe.toByte(), 0x00))
        assertFailsWith<CharacterCodingException> { TextDocuments.importFile(input, File(root, "bad.txt")) }
        input.writeText("valid")
        for (name in listOf("UTF-16", "ISO-8859-1", "windows-1252", "unknown")) {
            assertFailsWith<IllegalArgumentException> { TextDocuments.importFile(input, File(root, "bad.txt"), name) }
        }
        input.writeBytes(byteArrayOf(0xff.toByte(), 0xfe.toByte()) + "中文".toByteArray(Charsets.UTF_16LE))
        assertFailsWith<IllegalArgumentException> { TextDocuments.importFile(input, File(root, "bad.txt"), "UTF-8") }
    }

    @Test fun longUnpunctuatedLinesAndEmojiAreBoundedAndRoundTripExactly() = inDirectory { root ->
        val text = "a".repeat(3999) + "😀" + "汉".repeat(16001) + "🚀".repeat(5001) + "z".repeat(4000)
        val (file, index) = import(root, text.toByteArray())
        assertTrue(index.pages.size > 5)
        assertTrue(index.chapters.isEmpty())
        for (page in 1..index.pages.size) {
            val value = TextDocuments.readPage(file, index, page)
            assertTrue(value.length in 1..4000)
            assertFalse(value.first().isLowSurrogate())
            assertFalse(value.last().isHighSurrogate())
            assertFalse('\uFFFD' in value)
        }
        assertEquals(3999, TextDocuments.readPage(file, index, 1).length)
        assertEquals(text, joined(file, index))
        assertFailsWith<IllegalArgumentException> { TextDocuments.readPage(file, index, 0) }
        assertFailsWith<IllegalArgumentException> { TextDocuments.readPage(file, index, index.pages.size + 1) }
    }

    @Test fun paginationPrefersLineBoundariesAndDoesNotInventChapters() = inDirectory { root ->
        val text = (1..30).joinToString("") { "普通正文" + "字".repeat(895) + "\n" }
        val (file, index) = import(root, text.toByteArray())
        assertTrue(index.chapters.isEmpty())
        assertTrue(index.pages.size > 1)
        for (p in 1..index.pages.size) {
            val value = TextDocuments.readPage(file, index, p)
            assertTrue(value.length <= 4000)
            assertTrue(value.endsWith('\n'))
        }
        assertEquals(text, joined(file, index))
    }

    @Test fun allChapterPatternsStartNewPagesAndLongBodyIsNotATitle() = inDirectory { root ->
        val titles = listOf("序章", "序言", "楔子", "第一章开始", "第2卷 远行", "第三节 重逢", "Chapter 1: Arrival", "CHAPTER IV End", "尾声", "后记")
        val longBody = "第十二章 " + "这是一段很长的正文".repeat(25) + "\n"
        val text = "开头介绍\n" + longBody + titles.joinToString("") { "$it\n这是正文。\n\n" } +
            "第一章里的事情发生了。\nChapter 9 tells a story.\n序言不是一段标题正文\n"
        val (file, index) = import(root, text.toByteArray())
        assertEquals(titles, index.chapters.map { it.title })
        assertEquals(2, index.chapters.first().page)
        assertEquals(index.chapters.map { it.page }.distinct().sorted(), index.chapters.map { it.page })
        for (chapter in index.chapters) {
            assertTrue(TextDocuments.readPage(file, index, chapter.page).startsWith(chapter.title + "\n"))
        }
        assertEquals(text, joined(file, index))
    }

    @Test fun indexesAreDeterministicAcrossEncodingAndRepeatedImports() = inDirectory { root ->
        val text = "说明\n第一章 起点\n" + "甲乙丙丁\n".repeat(3000) + "Chapter 2\n结尾"
        val (file, utf8) = import(root, text.toByteArray())
        val sidecar = File(file.path + ".index.json")
        val originalSidecar = sidecar.readText()
        val reopened = TextDocuments.open(file)
        assertEquals(originalSidecar, sidecar.readText(), "Valid sidecars should not be rewritten")
        assertEquals(utf8.pages, reopened.pages)
        val (_, repeated) = import(root, text.toByteArray())
        assertEquals(originalSidecar, sidecar.readText())
        assertEquals(utf8.pages, repeated.pages)
        val (_, gb) = import(root, text.replace("\n", "\r\n").toByteArray(Charset.forName("GB18030")))
        assertEquals(utf8.pages, gb.pages)
        assertEquals(utf8.chapters, gb.chapters)
    }

    @Test fun renamingCanonicalAndSidecarRetainsOriginalEncodingAndValidIndex() = inDirectory { root ->
        val text = "序章\n中文书籍\n" + "正文😀".repeat(2500)
        val (temp, index) = import(root, text.toByteArray(Charset.forName("GB18030")))
        val oldSidecar = File(temp.path + ".index.json")
        val sidecarText = oldSidecar.readText()
        val destination = File(root, "content-hash.txt")
        assertTrue(temp.renameTo(destination))
        assertTrue(oldSidecar.renameTo(File(destination.path + ".index.json")))
        assertTrue(destination.setLastModified(1234567890000))
        val reopened = TextDocuments.open(destination)
        assertEquals("GB18030", reopened.encoding)
        assertEquals(index.pages, reopened.pages)
        assertEquals(index.chapters, reopened.chapters)
        assertEquals(sidecarText, File(destination.path + ".index.json").readText())
        assertEquals(text, joined(destination, reopened))
    }

    @Test fun missingOldAndCorruptedIndexesAreRebuiltWithoutChangingText() = inDirectory { root ->
        val text = "说明\n第一章 开始\n" + "正文\n".repeat(3000)
        val (file, original) = import(root, text.toByteArray(Charset.forName("GB18030")))
        val sidecar = File(file.path + ".index.json")
        val valid = sidecar.readText()
        val corruptions = listOf<(JSONObject) -> Unit>(
            { it.put("version", 0) },
            { it.put("fileBytes", file.length() + 1) },
            { it.put("fileSha256", "0".repeat(64)) },
            { it.getJSONArray("pages").getJSONObject(0).put("byteOffset", Long.MAX_VALUE) },
            { it.getJSONArray("pages").getJSONObject(0).put("byteLength", -1) },
            { it.getJSONArray("pages").getJSONObject(0).put("byteLength", 1.5) },
            { it.getJSONArray("pages").getJSONObject(0).put("sha256", "0".repeat(64)) },
            { it.getJSONArray("pages").put(100000, JSONObject()) },
            { it.getJSONArray("chapters").put(10000, JSONObject()) },
            { it.getJSONArray("chapters").getJSONObject(0).put("page", 99999) },
            { it.getJSONArray("chapters").getJSONObject(0).put("title", "第二章 错误") },
            { it.put("indexSha256", "0".repeat(64)) },
        )
        val broken = listOf("", "not json", "{\"version\":1}") + corruptions.map { change ->
            JSONObject(valid).also(change).toString()
        }
        for (raw in broken) {
            sidecar.writeText(raw)
            val recovered = TextDocuments.open(file)
            assertEquals("UTF-8", recovered.encoding)
            assertEquals(original.pages, recovered.pages)
            assertEquals(original.chapters, recovered.chapters)
            assertEquals(text, joined(file, recovered))
            assertEquals(TextDocuments.VERSION, JSONObject(sidecar.readText()).getInt("version"))
        }
        assertTrue(sidecar.delete())
        assertEquals(original.pages, TextDocuments.open(file).pages)
        assertEquals(text, file.readText())
    }

    @Test fun changedSameLengthContentIsDetectedEvenWithSameTimestamp() = inDirectory { root ->
        val (file, original) = import(root, "正文甲\n".repeat(2000).toByteArray())
        val modified = file.lastModified()
        file.writeText(file.readText().replace('甲', '乙'))
        assertTrue(file.setLastModified(modified))
        assertFailsWith<IllegalArgumentException> { TextDocuments.readPage(file, original, 1) }
        val current = TextDocuments.open(file)
        assertEquals(file.readText(), joined(file, current))
        RandomAccessFile(file, "rw").use { it.setLength(1) }
        assertFailsWith<IllegalArgumentException> { TextDocuments.readPage(file, current, 1) }
        assertFailsWith<CharacterCodingException> { TextDocuments.open(file) }
    }

    @Test fun blankBinaryAndOversizedFilesAreRejectedWithoutReplacingExistingTarget() = inDirectory { root ->
        val (target, _) = import(root, "保留既有内容".toByteArray())
        val originalIndex = File(target.path + ".index.json").readBytes()
        val input = File(root, "invalid.bin")
        val invalid = listOf(
            byteArrayOf(), " \t\r\n\u3000\u00A0".toByteArray(), byteArrayOf(0xef.toByte(), 0xbb.toByte(), 0xbf.toByte()),
            byteArrayOf(0, 1, 2, 3, 4), byteArrayOf(0x50, 0x4b, 3, 4, 5), "text\u007fdata".toByteArray(),
        )
        for (bytes in invalid) {
            input.writeBytes(bytes)
            assertFailsWith<IllegalArgumentException> { TextDocuments.importFile(input, target) }
            assertEquals("保留既有内容", target.readText())
            assertContentEquals(originalIndex, File(target.path + ".index.json").readBytes())
        }
        RandomAccessFile(input, "rw").use { it.setLength(TextDocuments.MAX_INPUT_BYTES + 1) }
        assertFailsWith<IllegalArgumentException> { TextDocuments.importFile(input, target) }
        assertFailsWith<IllegalArgumentException> { TextDocuments.importFile(target, target) }
        assertEquals(listOf("canonical.txt", "canonical.txt.index.json", "invalid.bin", "original.bin"), root.list()!!.sorted())
    }

    @Test fun openRequiresCanonicalUtf8AndDoesNotChangeInvalidFiles() = inDirectory { root ->
        val file = File(root, "not-canonical.txt")
        for (bytes in listOf("a\rb".toByteArray(), "\uFEFFtext".toByteArray(), " \n\t".toByteArray(), byteArrayOf(0))) {
            file.writeBytes(bytes)
            assertFailsWith<IllegalArgumentException> { TextDocuments.open(file) }
            assertContentEquals(bytes, file.readBytes())
            assertFalse(File(file.path + ".index.json").exists())
        }
    }

    @Test fun chapterLimitRejectsOverflowWithoutTruncatingOrReplacingPublishedFiles() = inDirectory { root ->
        val text = "序章\n".repeat(10000)
        val (target, index) = import(root, text.toByteArray())
        assertEquals(10000, index.chapters.size)
        assertEquals(10000, index.pages.size)
        assertEquals(10000, index.chapters.last().page)
        assertEquals("序章\n", TextDocuments.readPage(target, index, 10000))
        assertEquals(10000, TextDocuments.open(target).chapters.size)
        val sidecar = File(target.path + ".index.json")
        val publishedIndex = sidecar.readBytes()
        val input = File(root, "original.bin").apply { appendText("序章\n") }
        val failure = assertFailsWith<IllegalArgumentException> { TextDocuments.importFile(input, target) }
        assertTrue(failure.message.orEmpty().contains("maximum 10000"))
        assertEquals(text, target.readText())
        assertContentEquals(publishedIndex, sidecar.readBytes())
        val canonical = File(root, "too-many.txt").apply { writeText(text + "序章\n") }
        assertFailsWith<IllegalArgumentException> { TextDocuments.open(canonical) }
        assertFalse(File(canonical.path + ".index.json").exists())
        assertFalse(root.listFiles()!!.any { it.extension == "tmp" })
    }

    @Test fun interruptedIoAbortsWithoutClearingInterruptOrChangingPublishedFiles() = inDirectory { root ->
        val (target, index) = import(root, "第一章 开始\n既有正文\n".toByteArray())
        val sidecar = File(target.path + ".index.json")
        val originalText = target.readText()
        val originalIndex = sidecar.readBytes()
        val input = File(root, "original.bin")
        val operations = listOf<() -> Unit>(
            { TextDocuments.importFile(input, target) },
            { TextDocuments.open(target) },
            { TextDocuments.readPage(target, index, 1) },
        )
        for (operation in operations) {
            Thread.currentThread().interrupt()
            try {
                assertFailsWith<InterruptedIOException> { operation() }
                assertTrue(Thread.currentThread().isInterrupted)
            } finally { Thread.interrupted() }
            assertEquals(originalText, target.readText())
            assertContentEquals(originalIndex, sidecar.readBytes())
        }
        assertFalse(root.listFiles()!!.any { it.extension == "tmp" })
    }

    @Test fun multiMegabyteLongLineUsesBoundedPagesWithMatchingStreamingDigest() = inDirectory { root ->
        val input = File(root, "large.raw")
        val block = ("汉字😀".repeat(256)).toByteArray()
        input.outputStream().buffered().use { out -> repeat(2048) { out.write(block) } }
        val file = File(root, "large.txt")
        val index = TextDocuments.importFile(input, file)
        assertTrue(index.pages.size > 500)
        val expected = MessageDigest.getInstance("SHA-256")
        input.inputStream().buffered().use { stream ->
            val buffer = ByteArray(8192)
            while (true) { val count = stream.read(buffer); if (count < 0) break; expected.update(buffer, 0, count) }
        }
        val actual = MessageDigest.getInstance("SHA-256")
        for (page in 1..index.pages.size) {
            val text = TextDocuments.readPage(file, index, page)
            assertTrue(text.length <= 4000)
            actual.update(text.toByteArray())
        }
        assertContentEquals(expected.digest(), actual.digest())
        assertEquals(index.pages, TextDocuments.open(file).pages)
    }
}
