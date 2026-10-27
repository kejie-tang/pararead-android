package org.pararead.probes

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.flow
import org.json.JSONArray
import org.json.JSONObject
import org.pararead.core.*

/** Fixed English -> Chinese, empty-glossary experiments. Compiled into tools and tests, never app main. */
internal class ResearchSentenceProvider(private val native: TranslationProvider, private val source: JSONObject,
    private val recordCall: (JSONObject) -> Unit = {}) : TranslationProvider {
    override fun generate(prompt: ModelPrompt) = flow {
        val textOnly = prompt.outputFormat == ModelOutputFormat.TEXT
        val rows = if (textOnly) JSONArray().put(JSONObject().put("text", prompt.user.removeSuffix("\n/no_think")))
            else JSONObject(prompt.user.removeSuffix("\n/no_think")).getJSONArray("sentences")
        val retryLevel = if (textOnly) 2 else if (prompt.system.contains("Required markers, each exactly once:")) 1 else 0
        val outputs = JSONArray()
        for (index in 0 until rows.length()) {
            val row = rows.getJSONObject(index)
            val request = translationProbeRequest(source, row.getString("text"), retryLevel)
            val record = JSONObject().put("system", request.prompt.system).put("user", request.prompt.user).put("markerStyle", request.markerStyle)
            recordCall(record)
            val raw = StringBuilder()
            var failure: Exception? = null
            val decoded = try {
                native.generate(request.prompt).collect { if (it is ProviderEvent.Delta) raw.append(it.text) }
                request.decode(raw.toString())
            } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) { failure = e; record.put("error", e.message); null }
            finally { record.put("raw", raw.toString()) }
            (native as? MeasuredTranslationProvider)?.metrics?.let {
                record.put("generatedTokens", it.generatedTokens).put("tokensPerSecond", it.tokensPerSecond)
            }
            if (textOnly && failure != null) throw failure
            // Invalid rows cannot be accidentally accepted; valid neighbours still reach the queue.
            outputs.put(JSONObject().put("id", row.optString("id")).put("translation", decoded ?: JSONObject.NULL))
        }
        emit(ProviderEvent.Complete(if (textOnly) outputs.getJSONObject(0).getString("translation") else JSONObject().put("translations", outputs).toString()))
    }
}
