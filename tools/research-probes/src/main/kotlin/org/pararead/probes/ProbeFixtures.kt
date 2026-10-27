package org.pararead.probes

import org.pararead.core.SourceSentence
import org.pararead.core.SpeedTest

internal fun queueProbeFixtures() = linkedMapOf(
    "short" to listOf(SourceSentence("a", SpeedTest.SOURCE, "p1")),
    "protected-batch" to listOf(SourceSentence("a", "The model processes 100 pages using 4 GB of memory [12].", "p1"),
        SourceSentence("b", "For ${'$'}E=mc^2${'$'}, retain the equation and see https://example.org/paper.", "p1")),
    "numeric-contrast" to listOf(SourceSentence("a", "Accuracy fell from 91.2% to 87.6%, whereas latency improved from 120 ms to 80 ms.", "p1"),
        SourceSentence("b", "Only 3 of the 50 documents failed; the other 47 were translated successfully.", "p1")),
    "negation" to listOf(SourceSentence("a", "The increase was not statistically significant.", "p1"),
        SourceSentence("b", "The result may indicate an association, but it does not establish causation.", "p1")),
    "escaped-text" to listOf(SourceSentence("a", "The author calls this a \"reading assistant\".\nKeep the meaning of both sentences.", "p1")),
    "long" to listOf(SourceSentence("a", SpeedTest.LONG_SOURCE, "p1")))

/** Additional sources fixed after the marker strategy was designed; no prompt tuning on these. */
internal fun heldOutProbeFixtures(): Map<String, List<SourceSentence>> = listOf(
    "memory-order" to "The proposed method reduces peak memory usage by 25% without changing the order of the input sentences.",
    "baseline-contrast" to "The baseline is faster on short inputs, but our method is more accurate on longer documents.",
    "batch-negation" to "Increasing the batch size did not improve accuracy and sometimes increased latency.",
    "causation" to "We observed a correlation, not evidence that the treatment caused the improvement.",
    "citation-scope" to "The results in [2, 5–7] apply only to the 32-bit implementation; they do not justify claims about the 8-bit model.",
    "formula-code-url" to "For ${'$'}x_i = x_{i-1} + 1${'$'}, keep `cache.get(key)` unchanged and see https://example.org/a?b=1&c=2.",
    "document-counts" to "Of the 80 samples, 12 failed and 68 passed; a failure is not counted as a successful translation.",
    "opposite-trends" to "The score increased from 62.4 to 71.8, while the error rate fell from 9.1% to 6.3%.",
    "cancel-resume" to "The reader saves completed sentences before cancellation. It resumes only the unfinished work after reopening.",
    "significance" to "The phrase \"statistically significant\" does not mean that an effect is large or useful in practice.",
    "unit-contrast" to "The value was 4 GB in Table 2, not 4 MB. These units must not be interchanged.",
    "speed-quality" to "The comparison controls the model file, six CPU threads, and a 4096-token context. Faster output alone does not establish better translation quality."
).associate { (name, text) -> "heldout-$name" to listOf(SourceSentence("a", text, "p1")) }
