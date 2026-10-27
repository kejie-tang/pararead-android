# Small-model evaluation for ParaRead

This report records alpha.4. Alpha.5 adds independent row commits and targeted JSON/text recovery; see the [new recovery results and remaining failures](format-recovery.md). The first-pass format and speed measurements below remain historical alpha.4 results.

Test date: 2026-10-27. Real downloaded GGUF weights, the production CPU/JNI runtime, six CPU threads, no GPU. Host: **Apple M1 Pro / macOS ARM64**. These measurements are not Dimensity 9400 results and cannot be converted into a promised tablet token/s.


## Alpha.4 result and practical choice

For **faster preview reading**, try **Qwen3-0.6B Q4_K_M**, `Qwen3-0.6B-Q4_K_M.gguf` from [Unsloth](https://huggingface.co/unsloth/Qwen3-0.6B-GGUF), 396,705,472 bytes. It is not a quality-equivalent replacement for 1.7B. The [official Q8_0](https://huggingface.co/Qwen/Qwen3-0.6B-GGUF) is a larger tested alternative, not a guarantee of better translation. The default download remains 1.7B.

The app now uses the successful ASCII protocol directly: `__PR0__` markers in prompts, drafts and strict per-row validation, plus JSON field instructions without an example answer. Duplicate, missing, mutated and cross-row markers are still rejected. Prompt/validator/cache versions advance to 6/5; upgrading regenerates translations rather than reusing old prompt results. Text-only Qwen2.5/`qwen2` ChatML is also supported.

[Final production matrix](small-model-final.json), **Apple M1 Pro CPU / six threads**, no GPU:

| Exact file | MB | Guided check | Protected-row queue | Warm output token/s | Warm completion seconds | Raw tg128 token/s |
| --- | ---: | --- | --- | ---: | ---: | ---: |
| Qwen3-1.7B-Q4_K_M.gguf | 1359.3 | 8/8 | passed | 56.7 | 4.38 | 52.4 |
| Qwen3-0.6B-Q4_K_M.gguf | 396.7 | 8/8 | passed | 113.9 | 2.08 | 107.2 |
| Qwen3-0.6B-Q4_0.gguf | 382.2 | 8/8 | passed | 108.2 | 2.10 | 107.5 |
| Qwen3-0.6B-Q8_0.gguf | 639.4 | 8/8 | passed | 93.1 | 2.46 | 90.8 |
| qwen2.5-0.5b-instruct-q4_k_m.gguf | 491.4 | 8/8 | passed | 133.3 | 2.08 | 127.6 |
| Qwen3.5-0.8B-Q4_0.gguf | 563.0 | 7/8 | passed | 85.1 | 2.80 | 81.7 |

Each warm row is the median of three calls with the fixed 158-word source. Raw engine results are also three-run medians. These were sequential, exploratory measurements in one ordered run, not randomized statistical rankings. Qwen3 0.6B Q4_K_M and 1.7B use the same tokenizer family; the measured raw ratio is about **2.05×**, while paragraph completion takes **2.08 vs 4.38 seconds**. This is not a measured Dimensity 9400 speedup.

The final six-model sweep uses the real production prompt, template, grammar and validator, with no response-marker adapter. Its system/user strings were checked against the successful ASCII diagnostic. All five Qwen3/Qwen2.5 configurations pass 8/8 structural/Chinese-text fixtures and the protected-row queue. Qwen3.5 0.8B passes **7/8**, losing the total-document marker in the numeric fixture; it is not the first recommendation in this revision.

**Meaning remains the limit:** several Qwen models, including 1.7B, mistranslate the direction of a latency change. Qwen3 0.6B can omit technical detail and confuse numeric roles even while preserving every marker. Qwen2.5 0.5B is fast but omits a sentence, drops memory efficiency, and uses incorrect terminology such as “发动机性能” for engine performance; it is not recommended for scientific reading. No structural-pass count is presented as an accuracy score.

LMT-60-0.6B was rechecked with the **new** production protocol: [0/3 smoke fixtures](lmt-current-protocol.json), copying English or renumbering markers. Its readable plaintext results still require a separate adapter. Hy-MT2 needs unavailable STQ support; the tested Haidass GGUF has runtime/output problems. Neither is offered as a drop-in app model.



## Android verification and final selection

API 36 ARM64 emulator (`Google sdk_gphone64_arm64`, 8 virtual CPUs, 6 GiB RAM), **six inference threads**, no GPU. This is not a physical Dimensity 9400 test. Android fixtures use two protected rows and the app’s empty glossary; the host’s four-row case uses a two-term glossary. These are additional workloads, not a controlled attribution of host/Android differences to the CPU backend.

| Exact variant | Direct Android checks | Actual queue | Warm translation token/s | Raw tg128 token/s |
| --- | --- | --- | ---: | ---: |
| [Qwen3 0.6B Q4_K_M](android-small-Qwen3-0.6B-Q4_K_M.json) | 4/4 | passed | 74.9 | 68.9 |
| [Qwen3 0.6B Q8_0](android-small-Qwen3-0.6B-Q8_0.json) | 4/4 | passed | 60.8 | 62.1 |
| [Qwen3 0.6B Q4_0](android-small-Qwen3-0.6B-Q4_0.json) | 3/4 | failed | 76.1 | 68.9 |
| [Qwen3 1.7B Q4_K_M](android-small-initial-Qwen3-1.7B-Q4_K_M.json) | 4/4 | not measured in this run | 36.1 | 35.4 |

Each speed is the median of three warm samples, using the same production benchmark code as the app. **Q4_K_M is the final fast-preview recommendation:** 4/4 direct Android checks and a one-call protected queue pass, around twice the baseline’s measured raw speed in this emulator. Q8_0 also passes both and is the official-source alternative.

**Q4_0 is not recommended after Android testing.** Despite 8/8 host checks, the Android workload drops a citation, and the actual queue’s bounded single-sentence repair fails. All failed outputs are retained; they are not counted as passes. Initial Android runs are kept separately as `android-small-initial-*.json`. Qwen3.5 0.8B similarly fails the protected Android case. Qwen2.5 0.5B passes the initial four structural checks but remains excluded for translation quality.

Passing the protocol does not mean faithful translation: the Q4_K_M Android short output calls memory efficiency “高效记忆”, and several variants confuse the roles of pages and memory. Read important claims alongside the source. These results establish a usable experimental speed option, not a reliable autonomous scientific translator.


## Original app prompt (v5)

Eight original fixtures cover a short sentence, the 158-word benchmark paragraph, four protected rows, quotes/newlines, negation/hedging, numeric contrasts, technical contrasts, and a two-sentence discourse. Each guided pass requires valid JSON, exact result IDs/count, exact per-row placeholders, and Chinese text. It does not certify meaning or completeness. Without-grammar diagnostics cover three fixtures.

Long output speeds are medians of three warm calls. Raw tg128 is the median of three pp512→tg128 engine runs, using ctx4096/batch512/ubatch128, GPU0/F16 KV, with no sampler/grammar/token callbacks. Different tokenizers and output lengths affect cross-model translation token/s. A fast incomplete or failed response is not a usable translation result. Warm-sample status in the original runner only checks structure; the eight guided fixtures additionally check for Chinese text. In particular, Qwen2.5-0.5B’s fast schema-only warm outputs are not translations.

| Model / quantization | File MB (decimal) | Guided check | Queue repair | Warm output token/s | Raw tg128 token/s |
| --- | ---: | --- | --- | ---: | ---: |
| Qwen3-0.6B-Q4_K_M.gguf | 396.7 | 3/8 | failed | 109.6 | 102.6 |
| Qwen3-0.6B-Q4_0.gguf | 382.2 | 7/8 | failed | 115.6 | 109.0 |
| qwen2.5-0.5b-instruct-q4_k_m.gguf | 491.4 | 3/8 | passed | 130.2 | 119.7 |
| qwen2.5-1.5b-instruct-q4_k_m.gguf | 1117.3 | 7/8 | passed | 66.5 | 65.0 |
| LFM2-700M-Q4_K_M.gguf | 468.6 | 6/8 | failed | 112.2 | 114.8 |
| LFM2.5-1.2B-Instruct-QAD-Q4_0.gguf | 695.8 | 5/8 | failed | 86.9 | 90.1 |
| Hy-MT2-1.8B-2Bit.gguf | 600.5 | Load failed | — | — | — |
| Qwen3-1.7B-Q4_K_M.gguf | 1359.3 | 7/8 | passed | 51.4 | 51.1 |
| Qwen3.5-0.8B-Q4_0.gguf | 563.0 | 8/8 | passed | 87.0 | 86.2 |
| LMT-60-0.6B.Q4_K_M.gguf | 484.2 | 1/8 | passed | — | 110.5 |
| haidass-translate-143m-q8_0.gguf | 188.2 | 0/8 | failed | — | 121.8 |
| Qwen3-0.6B-Q8_0.gguf | 639.4 | 5/8 | failed | 89.0 | 86.7 |

Raw outputs and failures: [main sweep](small-model-matrix.json), [translation-specific / Q8 sweep](tiny-translator-matrix.json). Exact source repositories, revisions, file sizes, SHA-256 values and model licenses: [manifest](small-model-manifest.json). No weights are committed or bundled.

## Meaning and completeness observations

- Qwen3-0.6B Q4_K_M sometimes copies English or the schema example. Q4_0 follows the task more often in this fixed-seed sample, but still corrupts protected markers. Both render falling latency as increasing latency in the numeric fixture. This is not a general ranking of quantization quality.
- Qwen2.5-0.5B often emits the schema example, produces garbled Chinese, or drops terminology. A successful protected-row queue is not evidence that the full document is translated faithfully.
- Qwen2.5-1.5B has a protected-marker failure in the numeric fixture. It is not a sub-billion model.
- LFM2-700M produces substantial awkward or incorrect terminology, mixes traditional and simplified Chinese, and omits the second sentence in the quote/newline fixture. LFM2.5 QAD Q4_0 also drops that sentence and changes placeholder brackets; QAD does not fix protocol compatibility here.
- The original default Qwen3-1.7B also copies the schema’s literal `...` on the newly added negation fixture. Qwen3.5-0.8B passes all eight structural checks but omits a sentence, leaves `prose` untranslated, and confuses the roles of pages and memory in one protected result.
- Hy-MT2-1.8B-2Bit fails native GGUF loading. The verified file produces a tensor-offset/type-layout mismatch in the pinned parser. Its model card requires STQ kernels, and the linked upstream PR is still open/draft and unmerged when checked. Its `hunyuan-dense` chat template is also outside the app whitelist. Neither file size nor vendor speed claims establish compatibility with this engine.

## Sources and screening

- [Qwen3-0.6B official card](https://huggingface.co/Qwen/Qwen3-0.6B); [official Q8 GGUF](https://huggingface.co/Qwen/Qwen3-0.6B-GGUF); [Unsloth Q4 GGUF](https://huggingface.co/unsloth/Qwen3-0.6B-GGUF).
- [Qwen2.5-0.5B Instruct GGUF](https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF). Actual GGUF metadata uses the `qwen2` architecture and ordinary ChatML, with no thinking prefill.
- [LiquidAI LFM2-700M](https://huggingface.co/LiquidAI/LFM2-700M), [LFM2.5 QAD GGUF](https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct-GGUF). Liquid weights use the custom LFM Open License v1.0; the app’s Apache license does not replace it.
- [NiuTrans LMT](https://github.com/NiuTrans/LMT), [LMT-60-0.6B original model](https://huggingface.co/NiuTrans/LMT-60-0.6B), [selected GGUF conversion](https://huggingface.co/mradermacher/LMT-60-0.6B-GGUF).
- [Haidass-Translate-143M original model](https://huggingface.co/DALabCommunity/Haidass-Translate-143M), [publisher-linked Q8 GGUF](https://huggingface.co/umeiko/Haidass-Translate-143M-GGUF). Published translation scores are not JSON/placeholder compatibility tests.
- [Hy-MT2 2Bit card](https://huggingface.co/tencent/Hy-MT2-1.8B-2Bit-GGUF), [required STQ kernel PR](https://github.com/ggml-org/llama.cpp/pull/19357).

## Reproduction

Historical v5, compact and ASCII-adapter experiments are reproducible at commit `0f36d5a`. The current runner uses the production v6 protocol. For the final six-model sweep, use `small-model-final-manifest.json` in the command below.

Download each exact revision/file in the manifest and verify the SHA-256. Weights belong under `artifacts/models/` and are excluded from Git. The host runner verifies the manifest again before inference.

```sh
PARAREAD_CPU_THREADS=6 scripts/gradle.sh -PhostLlama=true \
  -PcmakePath=/absolute/path/to/cmake :tools:host-llama:run \
  --args='matrix docs/reports/local-small-models.json docs/reports/small-model-final-manifest.json'
```

The matrix keeps failures as failures. A test-process exit code of zero means the report completed, not that every model passed.


## Prompt and wire-protocol experiments

A [Chinese instruction with a translated-content example](small-model-compact-prompt.json) did not solve the problem. Some tiny models copied the literal `中文译文` instead of translating. It was not adopted.

The [ASCII probe](small-model-ascii-probe.json) removes example translation content, describes the JSON fields in words, and replaces rare `⟦P0⟧` markers with ASCII `__PR0__`. Qwen3-0.6B Q4_K_M, Q4_0 and Q8_0, Qwen2.5-0.5B Q4_K_M, and the original Qwen3-1.7B all passed 8/8 structural/Chinese-text checks and the protected-row queue in this probe. The diagnostic adapter maps only exact ASCII markers back to the old validator, rejects Unicode-marker aliases, and preserves every raw response. It does not repair malformed markers or manufacture translations.

Semantic checks still matter. The 0.6B Q4_K_M short result omits the memory-efficiency detail; the Q4_0 result retains it. Several Qwen variants, including 1.7B, describe latency decreasing from 120 to 80 ms with the word “提升/提高”, which can reverse the intended numerical meaning. Qwen2.5-0.5B produces incomplete or awkward Chinese even when all IDs and markers are valid. These are exploratory fixed-seed observations, not a standardized translation-quality benchmark.

## Plaintext translation probe

The [plaintext diagnostic](tiny-translator-plain-probe.json) uses the models’ documented translation-style user instructions, no JSON grammar, the same native sampler (not the model cards’ beam-search settings), and the closed Qwen3 thinking prefill. This is a separate protocol, not an app compatibility pass.

LMT-60-0.6B translates the short, long, protected and negation samples into readable Chinese and preserves the exact protected markers. The long translation nevertheless omits “equations” from its first list. It is a useful candidate for a dedicated sentence-by-sentence plaintext adapter, but does not support the current JSON protocol reliably. A future adapter would need its own Android, cancellation, queue, glossary and placeholder checks.

The publisher-linked Haidass Q8 GGUF produces corrupt text or invalid UTF-8 in the pinned engine even without JSON constraints. This result applies to this exact GGUF/runtime pair; it does not establish the quality of the original Transformers checkpoint. It is not recommended for the app.


## Release verification

[Verification record](alpha4-verification.json): 34 core tests, 16 source contracts, 13 Android bridge/web checks, reader typecheck, and four real JNI regression cases passed. Android regression ran 12 cases: 11 passed and one unchanged opt-in live-download case was skipped. All three native speed/lifecycle UI cases passed, including rotation, cancellation, model release, persistence, export entry and reader resume; [raw UI benchmark report](app-speed-test-android-alpha4.json). Model compatibility failures remain explicit in their individual reports and are not included in this regression pass claim.

Android debug assembly, lint, distribution-content checks, model exclusion, notices, signature and 16 KiB alignment passed. The alpha.4 debug APK has the same certificate as alpha.3. We restored the emulator display settings and stopped the headless emulator used for these tests.
