package org.pararead.probes

import org.json.JSONObject
import org.pararead.core.ModelOutputFormat
import org.pararead.core.ModelPrompt
import java.text.Normalizer

/** Fixed English -> Chinese research prompts; not selected by the Android app. */
internal data class ProbeRequest(val prompt: ModelPrompt, val markerStyle: String, val markers: List<String>) {
    fun decode(raw: String): String {
        if (markerStyle == "ascii") return raw
        val pattern = if (markerStyle == "bracket") Regex("\\[\\[PR([0-9]+)]]") else Regex("<ph([0-9]+)/>")
        val found = pattern.findAll(raw).map { "__PR${it.groupValues[1]}__" }.toList()
        require(found.sorted() == markers.sorted() && !raw.contains(Regex("__PR[0-9]+__"))) { "ENCODED_PROTECTED_TOKEN_MISMATCH" }
        return pattern.replace(raw) { "__PR${it.groupValues[1]}__" }
    }
}

internal fun translationProbeRequest(source: JSONObject, text: String, retryLevel: Int = 0): ProbeRequest {
    val style = if (source.optBoolean("alternateMarkerRecovery") && retryLevel > 0) {
        if (retryLevel == 1) "bracket" else "xml"
    } else source.optString("markerStyle", "ascii")
    require(style in setOf("ascii", "bracket", "xml"))
    val markers = Regex("__PR[0-9]+__").findAll(text).map { it.value }.toList()
    val encoded = Regex("__PR([0-9]+)__").replace(text) {
        when (style) { "bracket" -> "[[PR${it.groupValues[1]}]]"; "xml" -> "<ph${it.groupValues[1]}/>"; else -> it.value }
    }
    val input = if (source.optBoolean("normalizeNfc")) Normalizer.normalize(encoded, Normalizer.Form.NFC) else encoded
    val target = source.optString("targetLabel", "Chinese")
    require(target in setOf("Chinese", "chinese"))
    val profile = source.optString("promptProfile", if (source.getString("file").startsWith("LMT")) "lmt" else "user-only")
    val (system, user) = when (profile) {
        "user-only" -> "" to "Translate the following text from English to Simplified Chinese.\n$input"
        "lmt" -> "" to "Translate the following text from English into Chinese:\nEnglish: $input\nChinese:"
        "rwkv-translation" -> "" to "English: $input\n\nChinese:"
        "immersive" -> "You are a professional, authentic machine translation engine." to
            "Translate the following source text to $target. Output translation directly without any additional text.\n    Source Text: $input\n\n    Translated Text:"
        else -> error("UNKNOWN_RESEARCH_PROMPT_PROFILE: $profile")
    }
    return ProbeRequest(ModelPrompt(system, user, outputFormat = ModelOutputFormat.TEXT), style, markers)
}
