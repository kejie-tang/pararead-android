package org.pararead.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import java.nio.file.Files

class TextProtocolTest {
    private fun message(type: String, payload: JSONObject) = Envelope(type, "s", "d", "sha256:hash:txt-v1", "q", 1, 1, payload)

    @Test fun textRequestsPassTheRealNativeEnvelopeParser() {
        for ((type, payload) in listOf(
            "text.page" to JSONObject().put("page", 2),
            "text.chapters" to JSONObject().put("offset", 100).put("count", 100),
        )) {
            val parsed = Envelope.parse(message(type, payload).json().toString())
            assertEquals(type, parsed.type)
            assertEquals(payload.toString(), parsed.payload.toString())
        }
        assertFailsWith<IllegalArgumentException> { Envelope.parse(message("text.unknown", JSONObject()).json().toString()) }
    }

    @Test fun maximumTextPagesAndChapterBatchesFitTheNativeBridge() {
        val page = message("text.page", JSONObject().put("page", 100000).put("text", "中".repeat(4000))).json().toString()
        val chapters = JSONArray().apply {
            repeat(100) { put(JSONObject().put("title", "第${it + 1}章 " + "中".repeat(70)).put("page", it + 1)) }
        }
        val catalog = message("text.chapters", JSONObject().put("chapters", chapters).put("total", 10000)).json().toString()
        assertTrue(page.toByteArray().size < Envelope.MAX_BYTES)
        assertTrue(catalog.toByteArray().size < Envelope.MAX_BYTES)
    }

    @Test fun ordinaryTextMayQuoteThePdfSignature() {
        val directory = Files.createTempDirectory("txt-signature").toFile()
        try {
            val text = "PDF signature example: %PDF-1.7\nOrdinary text.\n"
            val source = directory.resolve("notes.source").apply { writeText(text) }
            val target = directory.resolve("notes.txt")
            val index = TextDocuments.importFile(source, target)
            assertEquals(text, index.pages.indices.joinToString("") { TextDocuments.readPage(target, index, it + 1) })
        } finally { directory.deleteRecursively() }
    }
}
