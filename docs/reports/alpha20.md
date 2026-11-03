# ParaRead Android 0.2.0-alpha.20 validation

Release target: **0.2.0-alpha.20 / versionCode 20**. Baseline: immutable alpha.19 commit `591a8d2a7c5080f93900ccc591869c835ff53fb1`. This is prerelease source validation; successful remote CI, retained-certificate signed artifact checks and full anonymous download verification are additional mandatory publication gates. No older tag/release/asset is replaced.

## Changes since alpha.19

- PDF's collapsed-toolbar down-arrow overlay removed; reverse scroll and Tab/Escape still restore controls with annotation/input/selection guards and unchanged PDF.js geometry.
- PDF/TXT fullscreen exit X removed. Android Back exits fullscreen first; another Back returns to the library after acknowledged reading/annotation saves. Failed saves retain the document and edits.
- Wide Library/Models/Settings navigation is hidden while a document is selected and restored on return to the library.
- TXT left/right content-page swipes reserve the 28px screen edges for Android Back and exclude vertical gestures, selection, multitouch, controls and busy pages.
- Landscape bilingual split can be dragged or keyboard-adjusted and remembered at 25%–75% source width. Hidden-source and Read only remain single-column. IDs, cache identities and content/checkpoint pages are unchanged.
- TXT fullscreen toolbar has paired Show/Hide actions. Chapters use a scrollable modal, bounded lazy loading/retry, and percentage preview with explicit Go; empty chapter lists and single-page books are handled.
- Native exit shares the mode-switch busy guard and host/document fences; TXT global keyboard and central navigation reject all page turns during save freeze. The two initial implementation-review P1 findings were fixed and independently re-reviewed.
- No database-schema/data wipe, engine/prompt/global-cache-fingerprint change, model download or model inference.

## Version-20 local rerun

Machine-readable results: [alpha20-validation.json](alpha20-validation.json).

| Gate | Result |
| --- | --- |
| Android Web/bridge/native source contracts | **128/128 passed** |
| Upstream/source contracts | **16/16 passed** |
| Standalone chapter/progress suite | **2/2 passed**, including real modal UI |
| Full reader TypeScript | Passed |
| Core JVM | **94 total: 93 passed, 1 optional real-model test skipped** |
| App JVM | **18 passed** |
| Engine JVM | **9 passed**, fake runtime |
| Native-enabled ARM64 debug APK | Built; package/version checked as versionCode **20** |
| AndroidTest APK | Built; **not executed on a device** |
| Lint | **0 errors / 54 warnings** |
| Debug signature, ZIP/ELF 16KiB, licenses/no-model distribution | Passed |

Full native build used maximum two Gradle workers. It passed after bumping to version 20; these are not merely results carried over from the implementation-stage version-19 debug build. Local debug signing is **not** the retained public signing identity and must not be distributed as a public upgrade.

Actual desktop Edge using generated assets and real PDF.js passed normal PDF, original-only PDF, headless/background PDF, TXT and standalone modal tests. Evidence covers pointer/touch/keyboard interactions, saved split ratio, 205 lazy chapters, no-chapter percent positioning, single-page controls, fullscreen dismissal/recovery, failed-save retry, delayed-save navigation freeze, cache-only reopen, and durable page/job barriers. Layout checks include 640×360 landscape, tablets, 320px width, 200% UI text, light/dark themes, focus and touch targets.

Native/storage/translations/save-fault behavior is simulated in browser tests. **No Android device/emulator, instrumentation execution or real-model inference occurred for alpha.20.** AndroidTest includes immediate-Back split persistence and failed-exit retention/retry but was compiled only. Android system-edge gestures, TalkBack, rotation, soft keyboards, long novels and device PDF annotation-exit faults still require real-device acceptance. Earlier releases' model/device results remain historical.

Implementation-stage rationale/evidence: [reader-interactions.md](reader-interactions.md), [PDF chrome checks](pdf-chrome-no-reveal.md). Current browser proofs are embedded in the versioned JSON; local-only screenshots/logs stay outside committed release sources.

## Signing, installation and publication

Android 9+ / ARM64, `org.pararead.android`, versionCode **20**. The signed release is accepted only with APK v2 verification, exactly one signer and retained preview certificate SHA-256:

```text
e044c8f329f42465866add4d041f29c498009361241610c8ee2de43ae6e87a45
```

The preview certificate identifies as Android Debug; this is not a production-signing claim. Same-signed previews can upgrade in place while retaining documents, annotations, translations, models and reading state. Do not uninstall to bypass a signature mismatch, and do not use local/CI debug APKs as upgrade substitutes.

Publication additionally verifies package/version/ARM64 ABI, ZIP/ELF 16KiB alignment, all six exact frozen generated reader files (including `text-navigation.css`), original sample PDF only, pinned third-party licenses/notices, no model weights or private keys, SHA-256 checksums and full anonymous downloads of all three release assets. Only the final `public-downloads-verified` journal stage establishes completion. The release remains a prerelease and does not replace any earlier version.
