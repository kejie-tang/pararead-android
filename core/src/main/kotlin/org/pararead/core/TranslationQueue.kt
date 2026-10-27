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

/** One inference owner. Selection preempts speculative work; whole-book work enters one page at a time. */
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
        current?.let { (w, job) -> if (work.priority == 0 && w.priority >= 1) job.cancel(Preempted()) }
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
                if (cached == null) missing += s else w.committed(s, cached, key)
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
                var remaining = batch
                val reasons = mutableMapOf<String, String>()
                for (attempt in 0..2) {
                    currentCoroutineContext().ensureActive()
                    val retry = mutableListOf<SourceSentence>()
                    // Accepted rows survive a neighbour's failure. Only failed sentences are retried.
                    val groups = if (attempt == 0) listOf(remaining) else remaining.map { listOf(it) }
                    for (group in groups) {
                        currentCoroutineContext().ensureActive()
                        try {
                            val validated = infer(group, w, attempt)
                            for ((source, value) in validated.accepted) {
                                currentCoroutineContext().ensureActive()
                                val key = contentKey(source.text, w.config)
                                cache.put(key, source.text, value, w.config)
                                w.committed(source, value, key)
                            }
                            for ((source, reason) in validated.rejected) { retry += source; reasons[source.id] = reason }
                        } catch (cancel: CancellationException) { throw cancel }
                        catch (e: Exception) {
                            retry += group
                            group.forEach { reasons[it.id] = (e.message ?: "TRANSLATION_FAILED").take(512) }
                        }
                    }
                    remaining = retry
                    if (remaining.isEmpty()) break
                    if (attempt < 2) delay(200L shl attempt)
                }
                failures += remaining.map { it.id to reasons.getValue(it.id) }
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
    private suspend fun infer(batch: List<SourceSentence>, w: TranslationWork, attempt: Int): TranslationValidation {
        if (batch.size == 1 && batch[0].text.length > 1200) {
            val original = batch[0]
            val parts = StructuredTranslation.fragments(original.text)
            require(parts.size > 1) { "PROTECTED_SPAN_EXCEEDS_CONTEXT_BUDGET" }
            val translated = StringBuilder()
            for (part in parts) {
                val key = contentKey(part, w.config)
                val value = cache.get(key) ?: infer(listOf(original.copy(text = part)), w.copy(delta = { _, text -> w.delta(original, translated.toString() + text) }), attempt).let { result ->
                    require(result.rejected.isEmpty()) { result.rejected.first().second }
                    result.accepted.single().second
                }.also {
                    cache.put(key, part, it, w.config)
                }
                translated.append(value)
            }
            return TranslationValidation(listOf(original to translated.toString()), emptyList())
        }
        if (attempt == 2) {
            val source = batch.single()
            val (prompt, protected) = StructuredTranslation.textPrompt(source, w.config)
            var complete: String? = null
            // Unconstrained text is not shown as a draft before its protected content is checked.
            provider().generate(prompt).collect { event -> if (event is ProviderEvent.Complete) {
                check(complete == null); complete = event.text
            } }
            return TranslationValidation(listOf(source to StructuredTranslation.validateText(requireNotNull(complete), protected, w.config)), emptyList())
        }
        val (prompt, protected) = if (attempt == 1) StructuredTranslation.repairPrompt(batch.single(), w.config)
            else StructuredTranslation.prompt(batch, w.config)
        var result: String? = null
        val draft = StringBuilder(); val displayed = mutableMapOf<String, String>()
        provider().generate(prompt).collect { event ->
            when (event) {
                is ProviderEvent.Delta -> {
                    draft.append(event.text)
                    for ((s, text) in StructuredTranslation.drafts(draft.toString(), batch, protected)) {
                        if (displayed[s.id] != text) { displayed[s.id] = text; w.delta(s, text) }
                    }
                }
                is ProviderEvent.Complete -> { check(result == null); result = event.text }
            }
        }
        return StructuredTranslation.validateEach(requireNotNull(result), batch, protected)
    }
}
