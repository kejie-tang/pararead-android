package org.pararead.android

import org.junit.Assert.*
import org.junit.Test
import org.pararead.core.WholeDocumentJob
import org.pararead.core.WholeJobStatus

class LibraryTranslationNoticeTest {
    private val paused = WholeDocumentJob(17, "revision", "fingerprint", 6,
        nextPage = 3, status = WholeJobStatus.CANCELLED)

    @Test fun idlePauseIsBriefWithoutChangingTheResumeCursor() {
        val before = paused.json().toString()
        assertEquals(2500L, libraryPausedNoticeDuration(paused, null, needsReview = false))
        assertEquals(2500L, libraryPausedNoticeDuration(paused, LibraryTranslationState(), needsReview = false))
        assertEquals(before, paused.json().toString())
        assertEquals(3, paused.nextPage)
        assertEquals(2, paused.done)
    }

    @Test fun interruptedRunningJobIsAlsoBriefWhenNoWorkerIsActive() {
        assertEquals(2500L, libraryPausedNoticeDuration(paused.copy(status = WholeJobStatus.RUNNING),
            null, needsReview = false))
    }

    @Test fun queuedRunningAndErrorStatesNeverExpire() {
        for (status in WholeJobStatus.entries) {
            val job = if (status == WholeJobStatus.COMPLETED) paused.copy(status = status, nextPage = 7)
                else paused.copy(status = status)
            for (state in listOf(LibraryTranslationState(queued = true), LibraryTranslationState(running = true),
                LibraryTranslationState(error = "CONFIG_CHANGED"), LibraryTranslationState(error = "PARTIAL_FAILURE"))) {
                assertNull(libraryPausedNoticeDuration(job, state, needsReview = false))
            }
        }
    }

    @Test fun failuresReviewCompletionAndMissingJobsNeverExpire() {
        assertNull(libraryPausedNoticeDuration(paused.copy(failedPages = 1), null, needsReview = false))
        assertNull(libraryPausedNoticeDuration(paused, null, needsReview = true))
        assertNull(libraryPausedNoticeDuration(paused.copy(status = WholeJobStatus.ERROR), null, needsReview = false))
        assertNull(libraryPausedNoticeDuration(paused.copy(status = WholeJobStatus.COMPLETED, nextPage = 7),
            null, needsReview = false))
        assertNull(libraryPausedNoticeDuration(null, null, needsReview = false))
    }
}
