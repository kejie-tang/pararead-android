# alpha.16 — library pretranslation and source-copy validation

## Scope

Addresses the English-as-Chinese acceptance/cache defect reported with `qwen3:1.7b` and <https://arxiv.org/pdf/2610.33845v1>, and adds library-menu **Translate all / 全部翻译**. The original paper was **not** translated end to end, and real Qwen3:1.7b inference was **not** reproduced.

Machine-readable evidence: [local validation](alpha16-validation.json), [interactive reader browser](alpha16-browser.json), [small headless browser](alpha16-background-browser.json).

## Output and cache repair

The old structured-response validator checked JSON, row identity, nonempty text and protected tokens, but not the target language. Cache reads could also return such historical results without revalidation.

`core/.../TranslationQuality.kt` now supplies shared Chinese-target script/copy checks to structured output, text recovery, sentence caches, long fragments and `ReaderHost.restoreTranslations()`. Rejected cache entries become misses and use the existing finite recovery sequence; good records and configuration fingerprints are retained. Math, code, URLs, quantities and short proper names are treated conservatively. The explicitly labelled Mock provider remains a separate demo fingerprint.

This is **not semantic translation verification**. Chinese text can still be inaccurate or incomplete; proper names may intentionally remain untranslated. Small-model output should be checked against the source.

## Real local model check, with low load

The host's original Ollama GPU path reported zero GPU residency. An isolated service found an unusable Vulkan ICD and a ROCm `invalid device function`; no system driver or persistent environment configuration was changed. The user then explicitly approved **CPU-only, two-thread, tiny-sample testing**.

The existing **qwen3:0.6b** weights were reused with a temporary resource-limited alias: **two CPU threads, 1024 context tokens, no GPU**, and two short, original sentences. No GGUF was downloaded and no large PDF was used for inference. Temporary model aliases were removed; the original Ollama service and base model were retained.

- Native API: `think=false`.
- Ollama OpenAI-compatible API: `reasoning_effort=none`, in addition to the existing `enable_thinking=false` and `/no_think` request configuration. The old settings alone could exhaust a small token budget without a final translation.
- Normal Ollama-port detection is separate from model naming: a Qwen alias on an unrelated compatible server does not automatically add the Ollama-specific argument. Nonstandard Ollama endpoints can use the provider's explicit option.
- The real `TranslationQueue` test seeded an English cache copy and a valid Chinese neighbor. **One model request** repaired the bad record to Chinese, including the page meaning; the good record was unchanged. Reopening both entries used **zero** further model requests.

`OllamaSmallIntegrationTest` is explicitly opt-in through `PARAREAD_OLLAMA_BASE_URL` (and an optional resource-limited `PARAREAD_OLLAMA_MODEL`). Public CI skips this test; it does not connect to a developer's local service.

## Background implementation

- `app/.../BackgroundTranslations.kt`: per-document unique WorkManager work with `KEEP`, foreground notifications, waiting-without-a-model, exclusive foreground/background leases, joined cancellation, configuration checks and explicit partial-failure results. Queue order is not promised to be FIFO.
- `web/reader/src/background-translation.ts`: direct range-based PDF.js document loading, actual segmentation/translation client, no viewer initialization, page rendering, selection or reading-position writes. Completed page resources are trimmed.
- `page.saved` confirms page text was committed before publishing sentences. `job.saved` confirms durable page progress before advancing. Cancelling an obsolete translation does not discard another page's chunk assembly.
- `ReaderHost` serializes job updates, uses JSON compare-and-set ownership fencing, and publishes committed state inside a non-cancellable section. Stopping waits for scope/native cleanup. A metadata-save failure is not confused with an unconfirmed native release.
- Provider configuration, model path and cache identity come from one immutable `SharedPreferences.all` snapshot. Model mutation and retained benchmark lifetimes participate in foreground handoff.
- Room multi-instance invalidation lets Worker-owned connections update the library Flow. Background extraction may record page count and translation flags, but does not change a saved page/location or `DocumentRecord.opened`.
- `COMPLETED` with failed pages is **partial**, not fully translated. Explicit retry after completion scans valid caches and fills missing results. Resume preserves earlier failure counts; it does not silently promise to retry every earlier failed page.
- Old whole-document cursors lack the new job-level cache-policy proof. They are rescanned once before being treated as fully checked; good sentence caches are still reused. Cancelling before that rescan cannot certify old pages. No Room schema migration or global cache reset is introduced.

Opening a document or model/settings UI stops background jobs before allocating foreground resources. Resume from the document menu afterward. Android foreground-service quotas, process death and OEM restrictions can interrupt long tasks; uninterrupted background execution is not guaranteed.

## Automated evidence

- **69 Core JVM tests**, including the real local opt-in cache-repair test, all passed locally.
- **7 App JVM tests** for resource serialization/cancellation, latest scheduling state, stale failure suppression, frozen identity/secret-safe diagnostics and legacy cache-policy handling.
- **9 Engine JVM tests**; native-runtime test doubles are not Android GGUF inference.
- **38 Web/host-contract tests** and **16 upstream contracts**, plus TypeScript checking.
- Actual desktop Edge/PDF.js reader regression retained page-jump ownership, hover/click behavior, cache restoration, fullscreen controls and incremental annotation writing. The original six-page sample is about 15 KB; it is not a model stress test.
- Actual desktop Edge/PDF.js background regression uses an **869-byte, two-page generated PDF**. It verifies both persistence barriers, no next-page extraction while checkpoint ACK is held, reload starting only at page 2, cached restoration, no viewer module/canvas/reading writes, and cleanup. Its Native bridge, storage and translation results are **simulated**.
- Native-enabled ARM64 debug APK and instrumentation APK compiled; JVM, Lint, signature, ZIP/ELF alignment and distribution checks passed. Exact counts and Lint warnings are in the JSON report. Local build concurrency was limited to two workers.

**No Android device, emulator or instrumentation execution was performed.** Added instrumentation covers the detached two-page Worker, duplicate enqueue, cancellation before deletion, stale-writer fencing and cross-connection library invalidation, but compilation is not evidence of executing those cases. Desktop PDF.js tests do not establish detached Android WebView behavior on a physical device.

## Release and upgrade

Version **0.2.0-alpha.16**, **versionCode 16**, Android 9+ / ARM64. Before publication, the CI-built signed release must pass cryptographic signature and checksum checks and retain certificate SHA-256:

```text
e044c8f329f42465866add4d041f29c498009361241610c8ee2de43ae6e87a45
```

The local debug APK uses a different certificate and cannot replace an installed published preview. Only the retained-signature release artifact is intended for in-place upgrades. **Do not uninstall to bypass a signature error**: uninstalling deletes private documents, translations and models. No model weights, third-party paper or full extracted paper text are distributed.
