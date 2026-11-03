# Reader interaction improvements — implementation-stage evidence

Baseline: public `0.2.0-alpha.19` (`591a8d2a7c5080f93900ccc591869c835ff53fb1`). This report records implementation-stage validation before the alpha.20 version bump; those local validation APKs carried versionCode 19 and used the local **debug** certificate, not the public upgrade certificate. Release-target validation for these changes is recorded separately in [alpha.20](alpha20.md). Prior public tags and artifacts remain unchanged.

## Changes

1. PDF: removed the collapsed-toolbar downward-arrow button and its dead label/CSS API. Upward touch/wheel input and Tab/Escape still restore controls; annotations, selection, dialogs and synchronized scrolling retain their guards. No PDF.js toolbar geometry change.
2. PDF/TXT: removed the native bottom-right fullscreen exit X. Android Back first leaves fullscreen, then leaves the reader.
3. TXT: horizontal content swipes turn one stable content page. Vertical scrolling, selected text, multiple touches, interactive controls, busy pages and the two 28px screen-edge zones do not turn pages. Landscape bilingual columns can be resized with a 44px-hit-area separator, pointer capture or keyboard. Original-column ratio is clamped to 25%–75%, persisted in `reading.txt.columnRatio`, and does not affect page IDs/cache/checkpoints. Portrait tablets keep paragraph-aligned columns; narrow portrait views remain stacked; hidden-source/read-only views remain single-column.
4. Android: the wide Library/Models/Settings sidebar is hidden whenever a document is selected and returns in the library. Existing narrow-screen navigation is unchanged.
5. TXT: fullscreen “Show reading toolbar” now has a matching “Hide reading toolbar” action without changing native fullscreen state.
6. TXT: replaced the native chapter select with a labeled modal containing a vertically scrollable list, bounded lazy chapter loading, explicit retry and percentage preview/commit. No-chapter books retain progress navigation; single-page books disable meaningless progress changes. 0% means page 1, 100% means the final content page, not byte or intra-page progress. Titles are rendered as text only.

## Save boundaries found and closed during review

- Returning to the library now waits for the existing `reading.saved` / necessary annotation `save.finished` acknowledgements before detach. `leaveReader()` shares the mode-switch busy guard, fences both host and selected-document identity, retains the current reader on failure, and shows a localized retry message. The existing native cancellation/drain cleanup remains unchanged. This also closes an earlier exit/debounce gap exposed by the new persistent split setting.
- TXT's shared page-navigation entry and global keyboard handler both reject input while `body.inert` is set. The navigation modal is closed and active resize capture released before snapshot/freeze. Delayed-ack tests confirm no late page/save/viewport messages can replace the captured reading state; matched resume restores navigation.
- Independent review: the two initial P1 findings are closed; final **No issues found / OK with notes**. Notes concern real-device verification, not an outstanding P0–P2 source blocker.

## Principal paths

- Native: `app/src/main/java/org/pararead/android/{MainActivity,LibraryScreen}.kt`, localized `strings.xml`, `ReaderRecoveryUiTest.kt`.
- Shared PDF: `web/reader/src/reader-chrome.ts`, `pdf-reading-only.ts`, `upstream/main.ts`, `web/reader/android.css`.
- TXT: `web/reader/src/{text-reader,text-gestures,text-navigation}.ts`, `web/reader/{text,text-navigation}.css`, `text.html`.
- Build/CI gates: `scripts/build_reader.mjs`, `scripts/test_text_reader.mjs`, `scripts/test_text_navigation.mjs`; the existing `test:text-reader` command includes the standalone modal/percentage suite. PDF browser harnesses support `PARAREAD_REPORT_DIR` to avoid replacing historical screenshots/background evidence.

## Verification

- Android Web tests: **128/128 passed**; upstream/source contracts: **16/16 passed**; full reader TypeScript: passed.
- TXT generated-assets desktop Edge: touch-dispatched left/right swipes, vertical/edge exclusions, split drag/keyboard/reopen, 640×360 landscape and tablet paragraph alignment, fullscreen show/hide, percentage preview/commit, 205 lazy chapters, empty chapter list, delayed-ack navigation freeze, read-only and headless-background isolation: passed.
- Standalone TXT navigation suite: **2/2 passed**, including percentage endpoint/single-page checks and real modal keyboard isolation, retry, disposal, 320px width and 200% UI text.
- Normal PDF, generated original-only PDF and real PDF.js background browser suites: passed. Normal PDF includes touch/keyboard chrome recovery without overlays, view geometry, cached reopen and incremental annotation saves; original-only includes successful and fault-injected mode-flush save barriers. Background verifies durable page/job acknowledgements, resume and intact translation cache. The harness now returns 204 for favicon requests rather than recording a harmless 404.
- Full native-enabled ARM64 debug APK and AndroidTest APK: **BUILD SUCCESSFUL**, maximum 2 workers. Core **93 passed / 1 optional real-model test skipped**; App **18 passed**; Engine **9 passed** using fake runtime. Lint **0 errors / 54 warnings** (existing baseline; the removed fullscreen resource was cleaned up).
- APK debug signature, ZIP/ELF 16KiB alignment, native libraries, licenses, sole original sample and no-model distribution checks: passed. All six current generated reader files, including `text-navigation.css`, were independently compared with packaged APK entries and matched exactly.
- APK SHA-256 (local validation only): `c61ae9b9fd0e137f398c58731f6666f16d75370b7217a17d11d6b3c0c3c1a311`.

Evidence:

- `docs/reports/txt-reader-browser.json`, `docs/reports/reading-only-pdf-browser.json`.
- `output/reader-interactions/android-reader-browser.json`, `background-reader-browser.json`.
- `output/reader-interactions/txt-landscape-resizable.png`, `txt-chapters-progress.png`.
- Final Web/browser log: `C:/Users/57452/AppData/Local/Temp/pi-background-terminal/bg_bba4552d256145ff.log` (`READER_INTERACTIONS_ALL_WEB_AND_BROWSER_GATES_PASSED`).
- Final native log: `C:/Users/57452/AppData/Local/Temp/pi-background-terminal/bg_1f240db77349720b.log` (`LOCAL FULL NATIVE BUILD, UNIT TESTS, LINT AND APK VALIDATION PASSED`).

## Limits

Browser evidence uses desktop Edge with actual generated reader resources and simulated Native/storage/translations/save faults. It is not Android WebView/system-edge-gesture evidence. AndroidTest sources include immediate-Back split persistence and failed-exit retention/retry, but were **compiled, not executed**. No Android device/emulator, model download or real-model inference was used. Real-device checks should cover system side Back, two-stage fullscreen Back, long novels, rotation, soft keyboard, screen readers and PDF annotation-save exit failures before release.
