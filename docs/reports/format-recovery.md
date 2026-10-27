# Small-model format recovery — alpha.5

Test date: 2026-10-27. The app can recover more format failures while retaining successful sentences. Recovery does not certify translation accuracy or guarantee that every failure is recoverable.

## Production behaviour

1. Generate the normal batch with the existing JSON grammar and v6 ASCII-marker prompt.
2. Parse the complete JSON envelope and require unique, known sentence IDs. Validate each row independently. Commit and cache accepted rows immediately; a neighbour's missing citation no longer discards them. Missing rows, empty/wrong-type translations and protected-marker failures are tracked per sentence. Ambiguous IDs, incomplete JSON or trailing prose cannot contribute accepted rows.
3. Retry only failed sentences individually, adding an explicit list of required markers to the translation instructions.
4. On the final attempt, request one sentence as plain translated text. The app already owns its ID, so the model need not generate JSON. Native JSON grammar and API `response_format=json_object` are disabled only for this TEXT request. The text must still pass exact protected-marker checks, output bounds, wrapper/reasoning checks and, for Chinese targets, a Chinese-text check before it is committed or displayed.
5. After the finite retry budget is exhausted, mark only the remaining sentences as failed. Their original text remains available for retry. Never append a missing citation, duplicate a number, guess a marker's position, or cache invalid recovery text.

There is one initial batch attempt and at most two further attempts per failed sentence. Successfully cached sentences are not regenerated on reopening. Cancellation still waits for native generation to stop; plaintext recovery drafts do not enter the UI or cache before validation. Validator/cache identity advances to `validator=6;recovery=sentence-text-v1`, so upgrading regenerates translations under this policy. The normal first-pass prompt and native JSON grammar are unchanged from alpha.4.

## Actual Android example

API 36 ARM64 emulator (`Google sdk_gphone64_arm64`, 8 virtual CPUs, 6 GiB RAM), CPU inference with six threads. **No physical Dimensity 9400 test.** Exact model files/revisions/hashes are in the individual reports and [manifest](format-recovery-manifest.json).

Source: `The model processes 100 pages using 4 GB of memory [12].`

The first Qwen3-0.6B Q4_0 batch answer still drops `__PR2__` (the citation):

```json
{"id":"s1","translation":"模型使用了 __PR0__ 页，其中 __PR1__ 页。"}
```

Its neighbouring equation/URL row passes exact validation and is saved immediately. Only the failed first sentence is regenerated. The real targeted retry returns:

```json
{"translations":[{"id":"s1","translation":"模型使用 __PR0__ 页，使用 __PR1__ 的内存 __PR2__。"}]}
```

The app restores `100`, `4 GB`, `[12]` and commits the sentence. Both rows are cached, and reopening makes zero provider calls. This improves protected-content completeness, although “模型使用 100 页” is still awkward translation.

Qwen3.5-0.8B fails both JSON attempts on the same first sentence; its real final TEXT output is:

```text
该模型使用__PR0__页，使用__PR1__个内存__PR2__。
```

All three markers are present exactly once, so it is restored and committed. The unnatural memory wording remains a translation-quality issue.

## Android results

The table refers to the same protected two-row workload. Counts are provider calls, not token/s or a measured speedup. These tests are separate from PocketPal tg128.

| Model | First-pass protected case | Production queue | Calls | Commit order | Reopen calls |
| --- | --- | --- | ---: | --- | ---: |
| [Qwen3-0.6B Q4_0](format-recovery-android-Qwen3-0.6B-Q4_0.json) | citation missing | passed via targeted JSON | 2 | good second row, repaired first row | 0 |
| [Qwen3.5-0.8B Q4_0](format-recovery-android-Qwen3.5-0.8B-Q4_0.json) | citation missing | passed via TEXT | 3 | good second row, repaired first row | 0 |
| [Qwen3-0.6B Q4_K_M](format-recovery-android-Qwen3-0.6B-Q4_K_M.json) | passed | passed directly | 1 | first row, second row | 0 |

Direct failures are retained in the reports. A passing queue does not relabel the first model response as a pass. Q4_K_M remains the fast-preview recommendation because this workload needs no recovery; the default download stays 1.7B.

We also deliberately replayed the two known incomplete Q4_0 JSON answers to exercise the actual native TEXT route, independent of whether a model's first pass succeeded. The reports explicitly label those answers as **replayed failures**:

- [Q4_0 forced TEXT](format-recovery-android-Qwen3-0.6B-Q4_0-forced-text.json): real TEXT generation passed, both rows cached.
- [Q4_K_M forced TEXT](format-recovery-android-Qwen3-0.6B-Q4_K_M-forced-text.json): real TEXT generation copied English and was rejected with `EXPECTED_CHINESE_TRANSLATION`. Only the already valid neighbouring row was cached. This research case remains failed; it demonstrates a remaining recovery limit and the refusal to cache invalid fallback output.

## Host checks and limits

[Real production-queue matrix](format-recovery-host.json), Apple M1 Pro / macOS ARM64, six CPU threads: six workloads cover short text, protected rows, numeric contrasts, negation, quotes/newlines and the 158-word benchmark paragraph.

- Qwen3-0.6B Q4_K_M, Q4_0, Q8_0 and Qwen3-1.7B Q4_K_M: 6/6 queue workloads passed.
- Qwen3.5-0.8B Q4_0: 5/6. The numeric workload still omits a required document-count marker after recovery; its independently valid neighbour survives. This failed case is retained.
- Additional replay cases use the recorded Android first answer and real host-model retries; they are labelled separately from fresh model responses. Direct text-only diagnostics include their failures and are not counted as production-queue passes.

Format-valid outputs still contain terminology mistakes and incorrect numerical relationships, including latency wording. No model is promoted to a reliable scientific translator on the strength of these format tests. LMT-60 and other specialized models are not newly declared compatible by this change.

Reproduce after downloading the exact manifest files and checking their hashes:

```sh
PARAREAD_HARDWARE='Apple M1 Pro' scripts/gradle.sh -PhostLlama=true \
  -PcmakePath=/absolute/path/to/cmake :tools:host-llama:run \
  --args='recovery docs/reports/local-recovery.json docs/reports/format-recovery-manifest.json'
```

The runner preserves failures; its successful exit means report generation completed. On Android, `ModelCompatibilityTest` accepts `modelPath` and `modelLabel`; optional `replayFailureForText=true` selects the explicitly labelled fault-injection case. No weights are committed or bundled.

## Release checks

[Verification record](alpha5-verification.json): 41 core tests, 16 source contracts, 13 Android bridge/web checks, [four real JNI regression cases](format-recovery-host-regression.json), and seven Android reader/whole-document/native-speed UI regression cases passed. The UI run covers rotation, cancellation, model release, result persistence and reader resume; [raw benchmark report](app-speed-test-android-alpha5.json). Failed model research cases remain failed in their reports and are separate from this regression claim.

Android assembly, lint, distribution-content checks, model exclusion, notices, signature and 16 KiB alignment passed. The APK uses the same debug certificate as alpha.4. The temporary model files were removed, the emulator display size was restored and the headless test emulator was stopped.
