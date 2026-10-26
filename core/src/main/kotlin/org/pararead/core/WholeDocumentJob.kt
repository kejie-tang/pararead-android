package org.pararead.core

import org.json.JSONObject

enum class WholeJobStatus { RUNNING, CANCELLED, COMPLETED, ERROR }

/** Cursor advances only after a complete page and its translations have been persisted. */
data class WholeDocumentJob(
    val jobId: Long, val revision: String, val fingerprint: String, val total: Int,
    val nextPage: Int = 1, val failedPages: Int = 0, val status: WholeJobStatus = WholeJobStatus.RUNNING,
) {
    init {
        require(jobId in 1..9007199254740991L && total in 1 until Int.MAX_VALUE)
        require(revision.isNotEmpty() && fingerprint.isNotEmpty())
        require(nextPage in 1..total + 1 && failedPages in 0 until nextPage)
        require(status != WholeJobStatus.COMPLETED || nextPage == total + 1)
    }
    val done get() = nextPage - 1
    val successfulPages get() = done - failedPages
    fun matches(revision: String, config: TranslationConfig, total: Int) =
        this.revision == revision && fingerprint == sha256(config.fingerprint()) && this.total == total
    fun progress(page: Int, failed: Boolean): WholeDocumentJob {
        require(status == WholeJobStatus.RUNNING && page == nextPage && page <= total) { "INVALID_JOB_CURSOR" }
        return copy(nextPage = page + 1, failedPages = failedPages + if (failed) 1 else 0)
    }
    fun finish(status: WholeJobStatus): WholeDocumentJob {
        require(status != WholeJobStatus.RUNNING)
        return copy(status = status)
    }
    fun json(): JSONObject = JSONObject().put("version", 1).put("jobId", jobId).put("revision", revision)
        .put("fingerprint", fingerprint).put("total", total).put("nextPage", nextPage)
        .put("failedPages", failedPages).put("status", status.name)
    companion object {
        fun parse(raw: String): WholeDocumentJob {
            val p = JSONObject(raw); require(p.getInt("version") == 1)
            return WholeDocumentJob(p.getLong("jobId"), p.getString("revision"), p.getString("fingerprint"),
                p.getInt("total"), p.getInt("nextPage"), p.getInt("failedPages"), WholeJobStatus.valueOf(p.getString("status")))
        }
    }
}
