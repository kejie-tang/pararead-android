package org.pararead.core

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class TranslationQueueTest {
    private class Cache : TranslationCache {
        val values = mutableMapOf<String, String>()
        val writes = mutableListOf<String>()
        override suspend fun get(key: String) = values[key]
        override suspend fun put(key: String, text: String, translation: String, config: TranslationConfig) {
            values[key] = translation
            writes += key
        }
    }

    private fun result(prompt: ModelPrompt): String {
        val sources = JSONObject(prompt.user.substringBefore("\n/no_think")).getJSONArray("sentences")
        val rows = JSONArray()
        for (i in 0 until sources.length()) {
            rows.put(JSONObject().put("id", sources.getJSONObject(i).getString("id")).put("translation", "完整的合格译文。"))
        }
        return JSONObject().put("translations", rows).toString()
    }

    @Test fun onlySelectionAndCurrentPagePreemptStrictlyLowerPriorities() = runTest {
        val preemptingPairs = setOf(0 to 1, 0 to 2, 0 to 3, 1 to 2, 1 to 3)
        for (incomingPriority in 0..3) for (runningPriority in 0..3) {
            val label = "incoming=$incomingPriority, running=$runningPriority"
            val begun = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val stopped = CompletableDeferred<Unit>()
            val runningDone = CompletableDeferred<Unit>()
            val incomingDone = CompletableDeferred<Unit>()
            val commits = mutableListOf<String>()
            var calls = 0
            val provider = object : TranslationProvider {
                override fun generate(prompt: ModelPrompt) = flow {
                    calls++
                    if (calls == 1) {
                        begun.complete(Unit)
                        try { release.await() } finally { stopped.complete(Unit) }
                    }
                    emit(ProviderEvent.Complete(result(prompt)))
                }
            }
            val queue = TranslationQueue(backgroundScope, Cache()) { provider }
            fun work(id: String, priority: Int, done: CompletableDeferred<Unit>) = TranslationWork(
                id, 0, priority, listOf(SourceSentence(id, "$id sentence.", "g")), TranslationConfig(),
                committed = { _, _, _ -> commits += id },
                finished = { failures, cancelled ->
                    assertFalse(cancelled, label)
                    assertTrue(failures.isEmpty(), label)
                    done.complete(Unit)
                })
            assertTrue(queue.submit(work("running", runningPriority, runningDone)))
            begun.await()
            assertTrue(queue.submit(work("incoming", incomingPriority, incomingDone)))
            val preempts = incomingPriority to runningPriority in preemptingPairs
            try {
                runCurrent()
                assertEquals(preempts, stopped.isCompleted, label)
                assertEquals(preempts, incomingDone.isCompleted, label)
                if (!preempts) assertEquals(1, calls, label)
            } finally { release.complete(Unit) }
            incomingDone.await()
            runningDone.await()
            assertEquals(if (preempts) listOf("incoming", "running") else listOf("running", "incoming"), commits, label)
            assertEquals(if (preempts) 3 else 2, calls, label)
        }
    }

    @Test fun queuedWorkUsesPriorityThenSubmissionOrderWithoutInterruptingExplicitOwner() = runTest {
        val begun = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val order = mutableListOf<String>()
        val completions = mutableListOf<CompletableDeferred<Unit>>()
        var calls = 0
        val provider = object : TranslationProvider {
            override fun generate(prompt: ModelPrompt) = flow {
                if (++calls == 1) { begun.complete(Unit); release.await() }
                emit(ProviderEvent.Complete(result(prompt)))
            }
        }
        val queue = TranslationQueue(backgroundScope, Cache()) { provider }
        fun submit(id: String, priority: Int) {
            val done = CompletableDeferred<Unit>()
            completions += done
            assertTrue(queue.submit(TranslationWork(id, 0, priority,
                listOf(SourceSentence(id, "$id sentence.", "g")), TranslationConfig(),
                committed = { _, _, _ -> order += id },
                finished = { failures, cancelled ->
                    assertFalse(cancelled)
                    assertTrue(failures.isEmpty())
                    done.complete(Unit)
                })))
        }
        submit("owner", 0)
        begun.await()
        submit("whole", 3)
        submit("speculative", 2)
        submit("visible-first", 1)
        submit("selected", 0)
        submit("visible-second", 1)
        runCurrent()
        assertEquals(1, calls)
        release.complete(Unit)
        completions.forEach { it.await() }
        assertEquals(listOf("owner", "selected", "visible-first", "visible-second", "speculative", "whole"), order)
    }

    @Test fun currentPageWaitsForCancellationDrainThenWholeWorkResumesUsingCompletedCacheRows() = runTest {
        val config = TranslationConfig(batchSize = 1)
        val cached = SourceSentence("cached", "Previously cached sentence.", "g")
        val completed = SourceSentence("completed", "Completed background sentence.", "g")
        val pending = SourceSentence("pending", "Pending background sentence.", "g")
        val visible = SourceSentence("visible", "Newly visible sentence.", "g")
        val cache = Cache()
        val cachedKey = contentKey(cached.text, config)
        cache.values[cachedKey] = "先前缓存的完整译文。"
        val fingerprint = config.fingerprint()
        val begun = CompletableDeferred<Unit>()
        val draining = CompletableDeferred<Unit>()
        val releaseDrain = CompletableDeferred<Unit>()
        val wholeDone = CompletableDeferred<Unit>()
        val visibleDone = CompletableDeferred<Unit>()
        val prompts = mutableListOf<ModelPrompt>()
        val commits = mutableListOf<String>()
        val finished = mutableListOf<String>()
        var owners = 0
        val provider = object : TranslationProvider {
            override fun generate(prompt: ModelPrompt) = flow {
                assertEquals(1, ++owners, "Inference must have only one owner")
                prompts += prompt
                try {
                    if (prompts.size == 2) {
                        begun.complete(Unit)
                        try { awaitCancellation() } finally {
                            withContext(NonCancellable) {
                                draining.complete(Unit)
                                releaseDrain.await()
                            }
                        }
                    }
                    emit(ProviderEvent.Complete(result(prompt)))
                } finally { owners-- }
            }
        }
        val queue = TranslationQueue(backgroundScope, cache) { provider }
        fun work(id: String, priority: Int, sources: List<SourceSentence>, done: CompletableDeferred<Unit>) = TranslationWork(
            id, 0, priority, sources, config,
            committed = { source, value, key ->
                assertEquals(contentKey(source.text, config), key)
                assertEquals(value, cache.values[key])
                commits += source.id
            },
            finished = { failures, cancelled ->
                assertFalse(cancelled)
                assertTrue(failures.isEmpty())
                finished += id
                done.complete(Unit)
            })
        assertTrue(queue.submit(work("whole", 3, listOf(cached, completed, pending), wholeDone)))
        begun.await()
        assertEquals(listOf("cached", "completed"), commits)
        assertTrue(queue.submit(work("visible", 1, listOf(visible), visibleDone)))
        try {
            runCurrent()
            assertTrue(draining.isCompleted, "Current page must promptly cancel background inference")
            assertEquals(2, prompts.size, "Next inference must wait for the cancelled provider to drain")
            assertEquals(1, owners)
            assertTrue(finished.isEmpty(), "Preemption must not finish the whole job")
        } finally { releaseDrain.complete(Unit) }
        visibleDone.await()
        wholeDone.await()
        assertEquals(listOf("visible", "whole"), finished)
        assertEquals(listOf("cached", "completed", "visible", "cached", "completed", "pending"), commits)
        assertEquals(listOf(completed.text, pending.text, visible.text, pending.text), prompts.map {
            JSONObject(it.user.substringBefore("\n/no_think")).getJSONArray("sentences").getJSONObject(0).getString("text")
        })
        assertEquals(listOf(completed, visible, pending).map { contentKey(it.text, config) }, cache.writes)
        assertEquals("先前缓存的完整译文。", cache.values[cachedKey])
        assertEquals(4, cache.values.size)
        assertEquals(fingerprint, config.fingerprint())
        assertEquals(0, owners)
    }
}
