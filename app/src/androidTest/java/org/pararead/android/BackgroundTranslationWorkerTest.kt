package org.pararead.android

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkInfo
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.pararead.core.*
import java.io.File
import java.util.UUID

/** Device-only integration coverage. Compilation is NOT evidence that detached WebView ran. */
@RunWith(AndroidJUnit4::class)
class BackgroundTranslationWorkerTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private fun twoPagePdf(): ByteArray {
        val objects = mutableListOf("<< /Type /Catalog /Pages 2 0 R >>", "<< /Type /Pages /Kids [3 0 R 5 0 R] /Count 2 >>")
        listOf("The reader remembers the last page.", "The library stores translations safely.").forEachIndexed { i, text ->
            val stream = "BT /F1 12 Tf 20 100 Td ($text) Tj ET"
            objects += "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 200] /Resources << /Font << /F1 7 0 R >> >> /Contents ${4 + i * 2} 0 R >>"
            objects += "<< /Length ${stream.length} >>\nstream\n$stream\nendstream"
        }
        objects += "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>"
        val pdf = StringBuilder("%PDF-1.4\n"); val offsets = mutableListOf(0)
        objects.forEachIndexed { i, obj -> offsets += pdf.length; pdf.append("${i + 1} 0 obj\n$obj\nendobj\n") }
        val xref = pdf.length
        pdf.append("xref\n0 ${offsets.size}\n0000000000 65535 f \n")
        offsets.drop(1).forEach { pdf.append(it.toString().padStart(10, '0')).append(" 00000 n \n") }
        pdf.append("trailer\n<< /Root 1 0 R /Size ${offsets.size} >>\nstartxref\n$xref\n%%EOF\n")
        return pdf.toString().toByteArray(Charsets.US_ASCII)
    }
    private suspend fun fixture(db: ReaderDatabase, bytes: ByteArray = twoPagePdf()): DocumentRecord {
        val id = "background-test-${UUID.randomUUID()}"
        val file = File(File(context.filesDir, "documents").apply { mkdirs() }, "$id.pdf").apply { writeBytes(bytes) }
        return DocumentRecord(id, "test:generated", "Small background test.pdf", file.path, "sha256:$id", bytes.size.toLong(), 1234L)
            .also { db.reader().document(it) }
    }
    private suspend fun finished(id: String): WorkInfo = withTimeout(90_000) {
        BackgroundTranslations.workInfos(context, id).first {
            BackgroundTranslations.latestByDocument(it)[id]?.state?.isFinished == true
        }.let { requireNotNull(BackgroundTranslations.latestByDocument(it)[id]) }
    }
    private fun withLibrary(test: suspend (ReaderDatabase) -> Unit): Unit = runBlocking {
        val settings = Settings(context)
        val saved = listOf(settings.provider, settings.targetLanguage, settings.glossary)
        val db = ReaderDatabase.open(context)
        try {
            settings.provider = "mock"; settings.targetLanguage = "zh-CN"
            settings.glossary = "{\"test\":\"background-${UUID.randomUUID()}\"}"
            ActivityScenario.launch<MainActivity>(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)).use { scenario ->
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                test(db)
                scenario.onActivity { activity ->
                    val field = MainActivity::class.java.getDeclaredField("host").apply { isAccessible = true }
                    assertNull("Pretranslation must never create a visible reader", field.get(activity))
                }
            }
        } finally {
            BackgroundTranslations.cancelAndAwait(context)
            settings.provider = saved[0]; settings.targetLanguage = saved[1]; settings.glossary = saved[2]
            db.close()
        }
    }
    @Test fun detachedWorkerTranslatesTwoSmallPagesWithoutReadingHistoryAndDeduplicates() = withLibrary { db ->
        val document = fixture(db)
        try {
            assertEquals(BackgroundTranslations.EnqueueResult.ENQUEUED, BackgroundTranslations.enqueue(context, document.id))
            assertEquals(BackgroundTranslations.EnqueueResult.ALREADY_QUEUED, BackgroundTranslations.enqueue(context, document.id))
            val work = finished(document.id)
            assertEquals(WorkInfo.State.SUCCEEDED, work.state)
            val job = WholeDocumentJob.parse(requireNotNull(db.reader().job("whole:${document.id}")).json)
            assertEquals(WholeJobStatus.COMPLETED, job.status); assertEquals(2, job.done); assertEquals(0, job.failedPages)
            val reading = org.json.JSONObject(requireNotNull(db.reader().reading(document.id)).json)
            assertFalse(reading.has("page")); assertFalse(reading.has("location"))
            assertEquals(1234L, requireNotNull(db.reader().document(document.id)).opened)
        } finally { BackgroundTranslations.cancelAndAwait(context, document.id); DocumentRepository(context, db.reader()).delete(document) }
    }
    @Test fun cancellingAQueuedDocumentDrainsBeforeDeletionWithoutReleasingAnotherOwner() = withLibrary { db ->
        val document = fixture(db)
        val owner = BackgroundTranslations.register("test-background-blocker")
        assertTrue(BackgroundTranslations.acquire(owner))
        try {
            assertEquals(BackgroundTranslations.EnqueueResult.ENQUEUED, BackgroundTranslations.enqueue(context, document.id))
            BackgroundTranslations.cancelAndAwait(context, document.id)
            assertEquals(WorkInfo.State.CANCELLED, finished(document.id).state)
            assertFalse(owner.cleaned.isCompleted)
            DocumentRepository(context, db.reader()).delete(document)
            assertNull(db.reader().document(document.id)); assertNull(db.reader().job("whole:${document.id}"))
        } finally {
            BackgroundTranslations.cancelAndAwait(context, document.id)
            BackgroundTranslations.cleaned(owner, null)
            DocumentRepository(context, db.reader()).delete(document)
        }
    }
    @Test fun separateWorkerConnectionInvalidatesDurableLibraryProgress() = runBlocking {
        val reader = ReaderDatabase.open(context); val writer = ReaderDatabase.open(context)
        val document = fixture(reader)
        val observedInitial = CompletableDeferred<Unit>()
        val projection = async {
            reader.reader().library().first { rows ->
                val job = rows.firstOrNull { it.document.id == document.id }?.translationJob()
                if (job == null) observedInitial.complete(Unit)
                job?.status == WholeJobStatus.COMPLETED
            }
        }
        try {
            withTimeout(10_000) { observedInitial.await() }
            val job = WholeDocumentJob(1, document.revision, sha256(TranslationConfig().fingerprint()), 2,
                nextPage = 3, status = WholeJobStatus.COMPLETED)
            writer.reader().job(JobRecord("whole:${document.id}", document.id, job.json().toString(), job.status.name))
            val rows = withTimeout(10_000) { projection.await() }
            assertEquals(2, rows.first { it.document.id == document.id }.translationJob()!!.done)
        } finally {
            projection.cancelAndJoin()
            DocumentRepository(context, reader.reader()).delete(document)
            reader.close(); writer.close()
        }
    }
    @Test fun jobCompareAndSetFencesOldWritersAndDeletedDocuments() = runBlocking {
        val db = ReaderDatabase.open(context); val document = fixture(db)
        try {
            val key = "whole:${document.id}"
            val first = JobRecord(key, document.id, "{\"owner\":\"first\"}", "RUNNING")
            val second = first.copy(json = "{\"owner\":\"second\"}")
            assertTrue(db.reader().saveJobIfCurrent(first, null))
            assertTrue(db.reader().saveJobIfCurrent(second, first.json))
            assertFalse(db.reader().saveJobIfCurrent(first, first.json))
            assertEquals(second, db.reader().job(key))
            DocumentRepository(context, db.reader()).delete(document)
            assertFalse(db.reader().saveJobIfCurrent(first, null))
        } finally { DocumentRepository(context, db.reader()).delete(document); db.close() }
    }
}
