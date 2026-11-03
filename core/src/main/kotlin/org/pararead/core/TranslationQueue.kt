package org.pararead.core

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.PriorityQueue
import java.util.concurrent.atomic.AtomicLong

interface TranslationCache {
    suspend fun get(key: String): String?
    suspend fun put(key: String, text: String, translation: String, config: TranslationConfig)
}
data class TranslationWork(val id: String, val epoch: Int, val priority: Int, val sentences: List<SourceSentence>, val config: TranslationConfig,
    val committed: suspend (SourceSentence, String, String) -> Unit,
    val finished: suspend (List<Pair<String, String>>, Boolean) -> Unit,
    val delta: suspend (SourceSentence, String) -> Unit = { _, _ -> })

/** One inference owner. Selection/current-page work preempts lower priorities; whole-book work enters one page at a time. */
class TranslationQueue(private val scope: CoroutineScope, private val cache: TranslationCache, private val provider: () -> TranslationProvider) {
    private class Preempted : CancellationException("SELECTION_PREEMPTED")
    private data class Entry(val work: TranslationWork, val order: Long)
    private val queue = PriorityQueue<Entry>(compareBy<Entry> { it.work.priority }.thenBy { it.order })
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val counter = AtomicLong()
    @Volatile private var current: Pair<TranslationWork, Job>? = null
    private val cancelled = mutableSetOf<String>()
    private var viewportEpoch = 0
    private val worker = scope.launch {
        for (ignored in wake) {
            while (isActive) {
                val entry = synchronized(queue) { queue.poll() } ?: break
                supervisorScope {
                    val task = launch(start = CoroutineStart.LAZY) { run(entry.work) }
                    current = entry.work to task
                    task.start(); task.join(); current = null
                }
            }
        }
    }
    fun submit(work: TranslationWork): Boolean {
        synchronized(queue) {
            if (queue.size >= 64 || queue.any { it.work.id == work.id } || current?.first?.id == work.id) return false
            queue.add(Entry(work, counter.incrementAndGet()))
        }
        current?.let { (w, job) -> if (work.priority in 0..1 && w.priority > work.priority) job.cancel(Preempted()) }
        wake.trySend(Unit)
        return true
    }
    fun cancel(id: String) {
        synchronized(queue) { if (queue.any { it.work.id == id } || current?.first?.id == id) cancelled.add(id) }
        val removed = synchronized(queue) { queue.filter { it.work.id == id }.also { queue.removeAll(it.toSet()) } }
        removed.forEach { scope.launch { it.work.finished(emptyList(), true); synchronized(queue) { cancelled.remove(it.work.id) } } }
        current?.let { if (it.first.id == id) it.second.cancel() }
    }
    fun viewport(epoch: Int) {
        viewportEpoch = epoch
        val obsolete = synchronized(queue) { queue.filter { it.work.priority in 1..2 && it.work.epoch != epoch }.map { it.work.id } }
        obsolete.forEach(::cancel)
        current?.let { (w, j) -> if (w.priority in 1..2 && w.epoch != epoch) j.cancel() }
    }
    fun cancelAll() {
        val ids = synchronized(queue) { queue.map { it.work.id } }
        ids.forEach(::cancel); current?.let { cancel(it.first.id) }
    }
    private suspend fun run(w: TranslationWork) {
        val failures = mutableListOf<Pair<String, String>>()
        try {
            val missing = mutableListOf<SourceSentence>()
            for (s in w.sentences) {
                currentCoroutineContext().ensureActive()
                val key = contentKey(s.text, w.config)
                val cached = cache.get(key)
                if (cached == null || !TranslationQuality.accepts(s.text, cached, w.config)) missing += s
                else w.committed(s, cached, key)
            }
            val batches = mutableListOf<List<SourceSentence>>()
            var pending = mutableListOf<SourceSentence>(); var chars = 0
            for (s in missing) {
                if (pending.isNotEmpty() && (pending.size >= w.config.batchSize.coerceIn(1, 40) || chars + s.text.length > w.config.maxChars)) {
                    batches += pending; pending = mutableListOf(); chars = 0
                }
                pending += s; chars += s.text.length
            }
            if (pending.isNotEmpty()) batches += pending
            for (batch in batches) {
                currentCoroutineContext().ensureActive()
                // One generation per batch/fragment. Quality diagnostics are ordinary text
                // warnings; failed streams retain attributable text without a repair loop.
                // Backend compatibility fallback remains owned by the provider, not this queue.
                val validated = try { infer(batch, w) }
                catch (cancel: CancellationException) { throw cancel }
                catch (e: Exception) {
                    failures += batch.map { it.id to (e.message ?: "TRANSLATION_FAILED").take(512) }
                    continue
                }
                for ((source, value) in validated.accepted) {
                    currentCoroutineContext().ensureActive()
                    try {
                        val key = contentKey(source.text, w.config)
                        cache.put(key, source.text, value, w.config)
                        w.committed(source, value, key)
                    } catch (cancel: CancellationException) { throw cancel }
                    catch (e: Exception) {
                        // A storage/commit failure must not erase a useful complete output.
                        // Publish only as a draft, never falsely acknowledge durable storage.
                        try { w.delta(source, value) } catch (cancel: CancellationException) { throw cancel } catch (_: Exception) { }
                        failures += source.id to (e.message ?: "TRANSLATION_FAILED").take(512)
                    }
                }
                failures += validated.rejected.map { (source, reason) -> source.id to reason }
            }
            w.finished(failures, false)
        } catch (e: CancellationException) {
            val resume = e is Preempted && scope.isActive && synchronized(queue) {
                w.id !in cancelled && (w.priority !in 1..2 || w.epoch == viewportEpoch)
            }
            if (resume) { synchronized(queue) { queue.add(Entry(w, counter.incrementAndGet())) }; wake.trySend(Unit) }
            else withContext(NonCancellable) { w.finished(failures, true) }
        } catch (e: Exception) {
            w.finished(w.sentences.map { it.id to (e.message ?: "TRANSLATION_FAILED").take(512) }, false)
        } finally {
            synchronized(queue) { cancelled.remove(w.id) }
        }
    }
    private suspend fun infer(batch: List<SourceSentence>, w: TranslationWork): TranslationValidation {
        if (batch.size == 1 && batch[0].text.length > 1200) {
            val original = batch[0]
            val parts = StructuredTranslation.fragments(original.text)
            require(parts.size > 1) { "PROTECTED_SPAN_EXCEEDS_CONTEXT_BUDGET" }
            val translated = mutableListOf<String>()
            try { for (part in parts) {
                val key = contentKey(part, w.config)
                val cached = cache.get(key)?.takeIf { TranslationQuality.accepts(part, it, w.config) }
                val value = cached ?: infer(listOf(original.copy(text = part)), w.copy(delta = { _, text ->
                        w.delta(original, translated.joinToString("") { TranslationWarnings.body(it) } + text)
                    })).let { result ->
                    require(result.rejected.isEmpty()) { result.rejected.first().second }
                    result.accepted.single().second
                }
                // Keep the newly received body even if writing its fragment fails.
                translated += value
                if (cached == null) cache.put(key, part, value, w.config)
            } } catch (cancel: CancellationException) { throw cancel }
            catch (e: Exception) {
                if (translated.isEmpty()) throw e
                return TranslationValidation(listOf(original to TranslationWarnings.append(
                    TranslationWarnings.join(translated), listOf(TranslationWarnings.errorCode(e)))), emptyList())
            }
            return TranslationValidation(listOf(original to TranslationWarnings.join(translated)), emptyList())
        }
        val (prompt, protected) = StructuredTranslation.prompt(batch, w.config)
        var result: String? = null
        val draft = StringBuilder(); val displayed = mutableMapOf<String, String>()
        suspend fun settled(validation: TranslationValidation): TranslationValidation {
            // Never let the Web fallback retain a draft rejected for unsafe/ambiguous ownership.
            for ((source, _) in validation.rejected) if (displayed.remove(source.id) != null) w.delta(source, "")
            return validation
        }
        try { provider().generate(prompt).collect { event ->
            when (event) {
                ProviderEvent.Restart -> {
                    draft.clear()
                    displayed.keys.forEach { id -> batch.find { it.id == id }?.let { w.delta(it, "") } }
                    displayed.clear()
                }
                is ProviderEvent.Delta -> {
                    draft.append(event.text)
                    val safe = StructuredTranslation.drafts(draft.toString(), batch, protected)
                    val safeIds = safe.map { it.first.id }.toSet()
                    for (id in displayed.keys.toList()) if (id !in safeIds) {
                        displayed.remove(id); w.delta(batch.first { it.id == id }, "")
                    }
                    for ((s, text) in safe) if (displayed[s.id] != text) { displayed[s.id] = text; w.delta(s, text) }
                }
                is ProviderEvent.Complete -> { check(result == null); result = event.text }
            }
        }
        val validated = StructuredTranslation.validateEach(requireNotNull(result), batch, protected, w.config)
        if (validated.rejected.isEmpty() || draft.isEmpty()) return settled(validated)
        val accepted = validated.accepted.toMutableList()
        val rejected = mutableListOf<Pair<SourceSentence, String>>()
        for ((source, reason) in validated.rejected) {
            val recovered = StructuredTranslation.salvage(draft.toString(), batch, protected, w.config, reason)
                .accepted.find { it.first.id == source.id }
            if (recovered == null) rejected += source to reason else accepted += recovered
        }
        return settled(TranslationValidation(accepted, rejected))
        } catch (cancel: CancellationException) { throw cancel }
        catch (e: Exception) {
            val reason = TranslationWarnings.errorCode(e)
            val recovered = StructuredTranslation.salvage(result ?: draft.toString(), batch, protected, w.config, reason)
            if (result != null && recovered.accepted.isEmpty() && draft.isNotEmpty() &&
                reason !in setOf("unknown_id", "duplicate_id")) {
                return settled(StructuredTranslation.salvage(draft.toString(), batch, protected, w.config, reason))
            }
            return settled(recovered)
        }
    }
}
