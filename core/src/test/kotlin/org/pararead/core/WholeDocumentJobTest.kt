package org.pararead.core

import org.junit.Test
import kotlin.test.*

class WholeDocumentJobTest {
    private val config = TranslationConfig(provider = "mock")
    private fun job() = WholeDocumentJob(123, "revision", sha256(config.fingerprint()), 3)

    @Test fun durableCursorRoundTripsWithSeparateSuccessAndFailureCounts() {
        val saved = job().progress(1, false).progress(2, true)
        val restored = WholeDocumentJob.parse(saved.json().toString())
        assertEquals(saved, restored)
        assertEquals(3, restored.nextPage)
        assertEquals(1, restored.successfulPages)
        assertEquals(1, restored.failedPages)
        assertEquals(WholeJobStatus.COMPLETED, restored.progress(3, false).finish(WholeJobStatus.COMPLETED).status)
    }
    @Test fun resumeRequiresTheSameDocumentAndTranslationIdentity() {
        assertTrue(job().matches("revision", config.copy(batchSize = 8), 3))
        assertFalse(job().matches("changed", config, 3))
        assertFalse(job().matches("revision", config.copy(targetLanguage = "ja"), 3))
        assertFalse(job().matches("revision", config.copy(modelHash = "new-weight"), 3))
        assertFalse(job().matches("revision", config, 4))
    }
    @Test fun partialCancelledOrOutOfOrderPagesCannotAdvanceTheCursor() {
        assertFailsWith<IllegalArgumentException> { job().progress(2, false) }
        assertFailsWith<IllegalArgumentException> { job().finish(WholeJobStatus.COMPLETED) }
        val cancelled = job().progress(1, false).finish(WholeJobStatus.CANCELLED)
        assertEquals(2, WholeDocumentJob.parse(cancelled.json().toString()).nextPage)
        assertFailsWith<IllegalArgumentException> { cancelled.progress(2, false) }
        assertEquals(3, cancelled.copy(status = WholeJobStatus.RUNNING).progress(2, true).nextPage)
    }
    @Test fun obsoleteOrCorruptRecordsAreRejected() {
        assertFails { WholeDocumentJob.parse("{\"jobId\":1,\"done\":2}") }
        assertFails { WholeDocumentJob.parse(job().json().put("version", 2).toString()) }
        assertFails { WholeDocumentJob.parse(job().json().put("nextPage", 5).toString()) }
        assertFails { WholeDocumentJob.parse(job().json().put("failedPages", 1).toString()) }
    }
}
