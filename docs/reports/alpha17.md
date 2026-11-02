# alpha.17 — cancellation feedback, cache reuse and current-page priority

## Scope

Follow-up to alpha.16. Source changes are limited to transient reader feedback, bounded cache-only reads, inference scheduling/ownership, their tests, version 17 and publication documentation. No model weights, third-party documents, private logs, signing keys or native engine changes are included. Translation fingerprints, database entities/schema version and valid caches remain unchanged.

## Fixes

- `MainActivity.kt` / `ReaderNotice.kt`: cancellation feedback dismisses after 2500 ms; a cancelled/stale effect cannot clear newer status. Empty status text does not occupy a row. Running progress/errors and durable library resume state are retained.
- `Database.kt` / `ReaderHost.kt`: one cache query per bounded bridge batch (<=40 rows), with the same source-copy/quality validation. Mark saved translations once per batch. No inference in the restoration handler.
- `translation-client.ts`: skip in-memory completed rows during restoration. Whole-book jobs reuse valid saved sentences and submit only misses. Current-page automatic work can take ownership from whole-book work. A resumed whole-book reply can recover an unowned failed/cancelled result, but cannot replace a newer pending or successful owner. The job still waits for its request and durable page checkpoint acknowledgement.
- `TranslationQueue.kt`: priority1 current-page work preempts running priority2/3 work, never an equal-priority request or explicit priority0. Cancellation drains before the next provider owner starts. Preempted whole-book work resumes through cache validation, so completed rows are not inferred again.

## Local validation

- TypeScript: passed.
- Web/host contracts: **42 passed**. Upstream contracts: **16 passed**.
- Core JVM: **72 tests, 71 passed, one opt-in real-model test skipped**, zero failures/errors. No inference service enabled for this follow-up.
- App JVM: **9 passed**. Engine JVM: **9 passed** (fake native runtime, not Android inference).
- Queue tests cover all 16 priority combinations, FIFO within priority, explicit-owner protection, cancellation drain/single ownership, and completed-row reuse during whole-job resumption.
- Web tests cover current-page promotion while a whole-book request remains outstanding, recovery after a visible-page failure, and fully/partly cached whole-book execution with zero/only-missing translation requests.
- Actual desktop Edge/PDF.js regression passed on the original approximately 15 KB six-page sample: automatic page2/page6 scheduling, prefetch-to-current ownership, cache/manual reopen, fullscreen/selection and incremental annotation saving. Native bridge/storage/translations are simulated in that browser test. A favicon HTTP404 remains in the report; it is not a PDF or translation failure. Historical browser reports/images were restored after the run.
- **Full native-enabled ARM64 debug APK and instrumentation APK compilation passed**, two Gradle workers, no `skipNative` for the final build. **Lint: 0 errors, 53 warnings**. APK signature, ZIP/ELF 16KiB alignment, ARM64 libraries, notices, sole original bundled PDF and no-GGUF distribution checks passed. This locally built APK uses a different debug certificate and is not a published-app upgrade package.

## Android emulator smoke observation

The local patched debug **smoke** build was run separately in a read-only existing Android API37 x86_64 emulator, two vCPUs/software rendering (the image enforces 4 GB guest RAM). This small UI build intentionally excluded native libraries and used the explicit debug mock/sample intent. It is distinct from the final native-enabled build above.

With the labelled Mock provider and original six-page approximately 15 KB sample:

- Cancellation feedback was visible in a screenshot about 504 ms after the tap command, and absent about 4094 ms later, with the blank row removed. The configured policy is 2500 ms; these observations are not millisecond-accurate frame timing measurements.
- Entering page6 in the reader's page-number field, without pressing a translation button, produced automatic explicitly labelled mock results.
- The temporary emulator was stopped and original persistent AVD data/configuration were not changed.

Screenshots are retained in the local validation artifacts rather than replacing historical repository images.

## Boundaries

No real model inference/download, physical-device or instrumentation execution was performed for these follow-up fixes. Engine tests use a fake runtime. Native-enabled APK compilation is not native on-device execution. The alpha.16 tiny, user-approved two-thread qwen3:0.6b CPU check is prior evidence, not a new alpha.17 measurement. No qwen3:1.7b or whole-paper reproduction is claimed.

Automatic scheduling cannot remove PDF extraction time, provider cancellation/drain time, or model inference cost. Explicit selection keeps its higher priority. Manual-mode preferences still require explicit translation. Cache reuse requires matching source/configuration and passing quality validation; malformed/English-copy entries remain misses.

## Release gate

VersionName `0.2.0-alpha.17`, versionCode `17`. CI and release workflow recheck the tagged source. The signed Draft must be downloaded and independently verified (all SHA-256 entries, cryptographic signature, retained certificate, version/package/ABI and ZIP alignment) before it becomes public.

Retained preview certificate SHA-256:

```text
e044c8f329f42465866add4d041f29c498009361241610c8ee2de43ae6e87a45
```

Same-signed upgrades retain private PDFs, models, translations and reading state. Never uninstall merely to bypass a signature mismatch.
