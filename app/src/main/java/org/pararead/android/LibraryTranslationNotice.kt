package org.pararead.android

import org.pararead.core.WholeDocumentJob
import org.pararead.core.WholeJobStatus

/** Hide only an idle, healthy pause notice; never mutate the durable job or hide errors. */
internal fun libraryPausedNoticeDuration(
    job: WholeDocumentJob?, state: LibraryTranslationState?, needsReview: Boolean,
): Long? = if (job != null && job.status in setOf(WholeJobStatus.CANCELLED, WholeJobStatus.RUNNING) &&
    job.failedPages == 0 && state?.active != true && state?.error == null && !needsReview
) 2500L else null
