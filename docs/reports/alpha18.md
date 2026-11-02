# alpha.18 — transient library pauses and inline translation errors

## Scope

Two UI follow-ups to alpha.17, versionName `0.2.0-alpha.18`, versionCode `18`. No engine, model, prompt, translation fingerprint, database entity/schema, or durable job format changes. Existing valid caches, reading position, recent-reading order, annotation/fullscreen behavior and manual preferences are retained.

## Changes

- `LibraryTranslationNotice.kt` and `LibraryScreen.kt`: the idle, healthy paused-progress row dismisses after 2500 ms, including its progress bar/spacing. The timeout only changes local Compose visibility. Durable job data and the document-menu resume action remain. Keys include document, job JSON and notice policy, so job/state changes cancel the old effect and immediately show active/error/review information. Known job errors, failed pages and review warnings never use this timeout.
- `panel.ts`: `renderSentence()` displays the localized failure/retry message inline for error states instead of falling back to the source. Pending text/drafts, accepted translations, sentence IDs, click/keyboard selection and retry behavior remain unchanged. Failed drafts are not cached or promoted to accepted output.
- `android.css`: error text has light/dark colors and long diagnostic codes wrap within the pane. Rendering uses `textContent`, not HTML interpretation. Existing raw diagnostics/localization are retained, not newly translated.
- `LibraryTranslationNoticeTest.kt`, `android-recovery.test.mjs`, `android-translation-errors.test.mjs`: pause policy, durable-cursor non-mutation, timer/row/menu contracts, draft-to-error display, cancellation, literal text and retry/accepted-neighbour regressions.

## Local checks

- TypeScript passed.
- **48 Android Web/host contracts + 16 upstream contracts = 64 passed**. Five new inline-error tests exercise the actual renderer and TranslationClient with controlled host events. Source-contract GAP tests document upstream behavior; they are not claims that every upstream gap is fixed.
- Core JVM: **72 total, 71 passed, one opt-in real-model test skipped**, zero failures/errors.
- App JVM: **13 passed**; four new pause-policy tests cover idle cancelled/interrupted jobs, active queued/running state, errors/failed pages, completion, missing jobs, review warnings and preserved cursor.
- Engine JVM: **9 passed**, fake native runtime, not device inference.
- Full native-enabled ARM64 debug APK and instrumentation APK compilation passed with two workers, without `skipNative` or forced refresh. Lint **0 errors / 53 warnings**. Debug cryptographic signature, ZIP/ELF 16KiB alignment, ARM64 engine libraries, notices, sole original sample and no-GGUF distribution checks passed. This debug APK is not a same-signed public upgrade package.
- Actual desktop Edge DOM: a Chinese draft becomes a visible inline error after a simulated failure, not the English original; retry replaces the error, good neighbouring output stays intact, long diagnostics wrap, markup remains literal text, and light/dark colors are checked. This isolated DOM check does not load a PDF or invoke Android/native inference. Historical reader screenshots/reports were not replaced.
- Machine-readable local evidence: [alpha18-validation.json](alpha18-validation.json).

## Boundaries

No new local Android emulator, physical-device, instrumentation execution, model inference/download or full-paper testing for alpha.18. Existing alpha.16 tiny CPU and alpha.17 Mock emulator observations are prior evidence only. The local full-PDF browser suite was not rerun for this isolated display change; the cloud workflows run their existing full PDF.js reader/background regression gates before publication.

This changes failure visibility, not model accuracy or validation acceptance. A visible draft is still unconfirmed; malformed/truncated/protected-token-invalid output can fail after retries. Waiting or cancelled requests retain the existing fallback behavior rather than becoming errors. Failure details are transient UI, not successful cache entries; reopening may restore good results or translate misses again.

## Publication gate

Push only the reviewed change allowlist on top of alpha.17. Require matching-source Android CI and signed-release workflow success; never overwrite an existing tag or release. Independently download the new Draft's APK/notices/checksum assets, check all hashes/sizes, cryptographically verify APK v2 and the retained certificate, package/version/ARM64 and 16KiB ZIP alignment before public prerelease publication. Do not mark it the latest stable release. Verify public page and all three anonymous download URLs afterwards.

Required retained certificate SHA-256:

```text
e044c8f329f42465866add4d041f29c498009361241610c8ee2de43ae6e87a45
```

Same-signed upgrades preserve private app data. Do not uninstall merely to bypass signature mismatch.
