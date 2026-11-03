package org.pararead.core

import org.json.JSONObject
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Uses the real parser; host matching and lifecycle are tested separately on Android. */
class ReaderFlushProtocolTest {
    private fun message(success: Boolean) = Envelope("reader.flushed", "session", "document", "revision",
        "flush-ack", 3, 1, JSONObject().put("flushId", "pending-flush").put("success", success))

    @Test fun readerFlushedSuccessPreservesSessionFenceAndPendingId() {
        val parsed = Envelope.parse(message(true).json().toString())
        assertEquals("reader.flushed", parsed.type)
        assertEquals("session", parsed.sessionId)
        assertEquals("document", parsed.documentId)
        assertEquals("revision", parsed.documentRevision)
        assertEquals(1, parsed.configRevision)
        assertEquals("pending-flush", parsed.payload.getString("flushId"))
        assertEquals(true, parsed.payload.getBoolean("success"))
    }

    @Test fun readerFlushedFailurePreservesSaveErrorWithoutChangingProtocolIdentity() {
        val message = message(false)
        message.payload.put("message", "SAVE_FAILED")
        val parsed = Envelope.parse(message.json().toString())
        assertEquals(false, parsed.payload.getBoolean("success"))
        assertEquals("SAVE_FAILED", parsed.payload.getString("message"))
        assertEquals(message.sessionId, parsed.sessionId)
        assertEquals(message.documentRevision, parsed.documentRevision)
    }

    @Test fun readerFlushedStillRejectsUnsupportedVersionUnknownTypeAndInvalidEnvelope() {
        val encoded = message(true).json()
        assertFailsWith<IllegalArgumentException> { Envelope.parse(encoded.put("version", 2).toString()) }
        encoded.put("version", 1)
        assertFailsWith<IllegalArgumentException> { Envelope.parse(encoded.put("type", "reader.flushUnknown").toString()) }
        encoded.put("type", "reader.flushed")
        assertFailsWith<IllegalArgumentException> { Envelope.parse(encoded.put("sessionId", "").toString()) }
    }
}
