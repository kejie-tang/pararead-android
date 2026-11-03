package org.pararead.android

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files

class TextDocumentSourceTest {
    private fun record(path: String, name: String = "renamed.pdf", size: Long = 3) =
        DocumentRecord("test", "content://original/test", name, path, "sha256:test:txt-v1", size)

    @Test fun automaticTextIdentityDoesNotCollideWithHistoricalPdfId() {
        val rawHash = "a".repeat(64)
        val pdf = documentSnapshotId(rawHash, text = false)
        val txt = documentSnapshotId(rawHash, text = true)
        assertEquals(rawHash, pdf)
        assertNotEquals(pdf, txt)
    }

    @Test fun explicitDecodingHasStableSeparateIdentity() {
        val rawHash = "b".repeat(64)
        val auto = documentSnapshotId(rawHash, text = true)
        val utf8 = documentSnapshotId(rawHash, text = true, encoding = "UTF-8")
        assertEquals(utf8, documentSnapshotId(rawHash, text = true, encoding = " utf-8 "))
        assertNotEquals(auto, utf8)
        assertNotEquals(utf8, documentSnapshotId(rawHash, text = true, encoding = "GB18030"))
    }

    @Test fun formatUsesSnapshotPathNotEditableTitle() {
        assertTrue(record("/private/documents/hash.txt").isText)
        assertTrue(record("/private/documents/hash.TXT").isText)
        assertFalse(record("/private/documents/hash.pdf", "novel.txt").isText)
    }

    @Test fun textRangeReadsUtf8SnapshotWithoutPdfAnnotationTail() {
        val directory = Files.createTempDirectory("txt-source").toFile()
        try {
            val bytes = "第一章\nText content.\n".toByteArray(Charsets.UTF_8)
            val file = directory.resolve("document.txt").apply { writeBytes(bytes) }
            val tail = directory.resolve("draft.suffix").apply { writeText("not text content") }
            val doc = record(file.path, size = file.length())
            RangeSource(doc, DraftRecord(doc.id, doc.revision, tail.path, tail.length())).use { source ->
                assertEquals(file.length(), source.length)
                assertArrayEquals(bytes, source.read(0, source.length))
                assertEquals(doc.revision, source.revision)
            }
        } finally { directory.deleteRecursively() }
    }

    @Test fun pdfIncrementalDraftRangesRemainUnchanged() {
        val directory = Files.createTempDirectory("pdf-source").toFile()
        try {
            val file = directory.resolve("document.pdf").apply { writeText("PDF") }
            val tail = directory.resolve("draft.suffix").apply { writeText("-draft") }
            val doc = record(file.path)
            RangeSource(doc, DraftRecord(doc.id, doc.revision, tail.path, tail.length())).use { source ->
                assertEquals(9L, source.length)
                assertEquals("F-dra", String(source.read(2, 7), Charsets.UTF_8))
                assertTrue(source.revision.contains(":draft:"))
            }
        } finally { directory.deleteRecursively() }
    }
}
