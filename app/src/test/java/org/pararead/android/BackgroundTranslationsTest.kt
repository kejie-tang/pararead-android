package org.pararead.android

import androidx.work.WorkInfo
import androidx.work.workDataOf
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.pararead.core.TranslationConfig
import org.pararead.core.WholeDocumentJob
import org.pararead.core.WholeJobStatus
import org.pararead.llama.InferenceBackend
import java.util.UUID

/** Cache-only JVM runnable as well: these tests never create a Context, WebView, model or Worker. */
class BackgroundTranslationsTest {
    @Test fun backgroundLeaseWaitsForRealCleanupAcknowledgement() = runBlocking {
        val first = BackgroundTranslations.register("first")
        val second = BackgroundTranslations.register("second")
        var firstCleaned = false
        var secondCleaned = false
        assertTrue(BackgroundTranslations.acquire(first))
        val waiting = async(start = CoroutineStart.UNDISPATCHED) { BackgroundTranslations.acquire(second) }
        try {
            assertFalse(waiting.isCompleted)
            assertFalse(first.cleaned.isCompleted)
            BackgroundTranslations.cleaned(first, null)
            firstCleaned = true
            assertTrue(first.cleaned.isCompleted)
            assertTrue(waiting.await())
            assertFalse(second.cleaned.isCompleted)
            BackgroundTranslations.cleaned(second, null)
            secondCleaned = true
        } finally {
            waiting.cancelAndJoin()
            if (!secondCleaned) BackgroundTranslations.cleaned(second, null)
            if (!firstCleaned) BackgroundTranslations.cleaned(first, null)
        }
    }

    @Test fun cancelledWaiterDoesNotReleaseAnotherWorkersLease() = runBlocking {
        val owner = BackgroundTranslations.register("owner")
        val cancelled = BackgroundTranslations.register("cancelled")
        val next = BackgroundTranslations.register("next")
        var ownerCleaned = false
        var nextCleaned = false
        assertTrue(BackgroundTranslations.acquire(owner))
        val waiter = async(start = CoroutineStart.UNDISPATCHED) {
            try { BackgroundTranslations.acquire(cancelled) }
            finally { BackgroundTranslations.cleaned(cancelled, null) }
        }
        val following = async(start = CoroutineStart.UNDISPATCHED) { BackgroundTranslations.acquire(next) }
        try {
            waiter.cancelAndJoin()
            assertTrue(cancelled.cleaned.isCompleted)
            assertFalse(following.isCompleted)
            BackgroundTranslations.cleaned(owner, null)
            ownerCleaned = true
            assertTrue(following.await())
            BackgroundTranslations.cleaned(next, null)
            nextCleaned = true
        } finally {
            waiter.cancelAndJoin()
            following.cancelAndJoin()
            if (!ownerCleaned) BackgroundTranslations.cleaned(owner, null)
            if (!nextCleaned) BackgroundTranslations.cleaned(next, null)
        }
    }

    @Test fun latestAttemptWinsRegardlessOfStateOrInputOrder() {
        fun info(document: String, created: Long, state: WorkInfo.State) = WorkInfo(UUID.randomUUID(), state,
            setOf(BackgroundTranslations.TAG, BackgroundTranslations.documentTag(document), "${BackgroundTranslations.CREATED_TAG_PREFIX}$created"))
        val oldFailure = info("a", 100, WorkInfo.State.FAILED)
        val newSuccess = info("a", 101, WorkInfo.State.SUCCEEDED)
        val otherWaiting = info("b", 99, WorkInfo.State.ENQUEUED)
        val result = BackgroundTranslations.latestByDocument(listOf(newSuccess, otherWaiting, oldFailure))
        assertEquals(mapOf("a" to newSuccess, "b" to otherWaiting), result)
        assertEquals(emptyMap<String, WorkInfo>(), BackgroundTranslations.latestByDocument(emptyList()))
    }

    @Test fun historicalWorkerFailureCannotOverrideANewerReaderCompletion() {
        val failure = WorkInfo(UUID.randomUUID(), WorkInfo.State.FAILED,
            setOf("${BackgroundTranslations.CREATED_TAG_PREFIX}90"), outputData = workDataOf(
                BackgroundTranslations.JOB_ID to 100L, BackgroundTranslations.ERROR_CODE to "PARTIAL_FAILURE"))
        val completed = WholeDocumentJob(101L, "revision", "fingerprint", 2, nextPage = 3, status = WholeJobStatus.COMPLETED)
        assertNull(BackgroundTranslations.failureFor(failure, completed))
        assertEquals("PARTIAL_FAILURE", BackgroundTranslations.failureFor(failure, completed.copy(jobId = 100L)))
        assertEquals("PARTIAL_FAILURE", BackgroundTranslations.failureFor(failure, null))
    }

    @Test fun frozenRuntimeIdentityAndDiagnosticTextDoNotFollowLiveSettingsOrExposeKeys() {
        val original = TranslationConfig(provider = "api", model = "model-A", modelHash = "endpoint:http://localhost:11434/v1")
        val snapshot = TranslationRuntimeSnapshot(original, "/private/model-A.gguf", 2, InferenceBackend.CPU,
            "http://localhost:11434/v1", "do-not-log-this-key")
        val later = original.copy(model = "model-B")
        assertEquals("model-A", snapshot.config.model)
        assertNotEquals(later.fingerprint(), snapshot.config.fingerprint())
        assertFalse(snapshot.toString().contains("do-not-log-this-key"))
        assertFalse(snapshot.toString().contains("/private/"))
    }

    @Test fun oldWholeDocumentCursorsMustBeRecheckedWithoutInvalidatingGoodSentenceCaches() {
        val old = WholeDocumentJob(1L, "revision", "fingerprint", 2, nextPage = 2)
        assertFalse(hasCurrentTranslationCachePolicy(old.json().toString()))
        val current = old.json().put("cachePolicy", TRANSLATION_CACHE_POLICY).toString()
        assertTrue(hasCurrentTranslationCachePolicy(current))
        assertFalse(hasCurrentTranslationCachePolicy("broken"))
        assertFalse(hasCurrentTranslationCachePolicy(null))
        assertEquals("Cancelling before revalidation cannot certify old pages", 0, checkpointCachePolicy(old.nextPage, old.json().toString()))
        assertEquals(TRANSLATION_CACHE_POLICY, checkpointCachePolicy(1, old.json().toString()))
        assertEquals(TRANSLATION_CACHE_POLICY, checkpointCachePolicy(2, current))
        val config = TranslationConfig()
        assertTrue(config.fingerprint().contains("validator=7"))
        assertEquals(listOf(false, true), org.pararead.core.translationCacheKeys("Saved sentence.", config).map { it.legacy })
    }

    @Test fun snapshotRequiresAnEffectiveSupportedTarget() {
        assertTrue(BackgroundTranslations.validConfig(TranslationConfig(targetLanguage = "zh-CN")))
        assertFalse(BackgroundTranslations.validConfig(TranslationConfig(targetLanguage = "auto")))
        assertFalse(BackgroundTranslations.validConfig(TranslationConfig(targetLanguage = "bad-target")))
        assertFalse(BackgroundTranslations.validConfig(TranslationConfig(provider = "unknown")))
    }
}
