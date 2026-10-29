package org.pararead.core

import org.json.JSONObject

data class Envelope(
    val type: String, val sessionId: String, val documentId: String, val documentRevision: String,
    val requestId: String, val viewportEpoch: Int, val configRevision: Int, val payload: JSONObject,
) {
    fun json() = JSONObject().put("version", 1).put("type", type).put("sessionId", sessionId)
        .put("documentId", documentId).put("documentRevision", documentRevision).put("requestId", requestId)
        .put("viewportEpoch", viewportEpoch).put("configRevision", configRevision).put("payload", payload)
    fun reply(type: String, payload: JSONObject) = copy(type = type, payload = payload)
    companion object {
        const val MAX_BYTES = 65536
        private val types = setOf("reader.ready", "session.opened", "session.error", "translation.request", "translation.cacheGet", "request.cancel",
            "page.visible", "page.textChunk", "page.cacheGet", "reading.save", "annotation.changed", "document.save",
            "save.chunk", "save.end", "external.open", "telemetry.event", "job.progress", "job.finished", "settings.chooseLanguage")
        fun parse(text: String): Envelope {
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_BYTES) { "MESSAGE_TOO_LARGE" }
            val j = JSONObject(text)
            require(j.getInt("version") == 1) { "UNSUPPORTED_VERSION" }
            val type = j.getString("type")
            require(type in types) { "UNKNOWN_TYPE" }
            fun id(name: String) = j.getString(name).also { require(it.length in 1..128) { "INVALID_ID" } }
            return Envelope(type, id("sessionId"), id("documentId"), id("documentRevision"), id("requestId"),
                j.getInt("viewportEpoch").also { require(it >= 0) }, j.getInt("configRevision").also { require(it >= 0) }, j.getJSONObject("payload"))
        }
    }
}
