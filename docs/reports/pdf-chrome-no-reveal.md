# PDF fullscreen chrome: no reveal arrow

## Implementation and ownership

- `web/reader/src/reader-chrome.ts:15`: removed the `#readerShowControls` button, down-chevron SVG, click/focus handler and obsolete label API. No replacement overlay is created.
- `web/reader/src/pdf-reading-only.ts:10`, `web/reader/src/upstream/main.ts:197`: removed obsolete PDF label arguments/calls. Native/TXT message compatibility remains untouched.
- `web/reader/android.css:63–66`: retained the existing collapse geometry rules; deleted only reveal-button CSS. PDF.js toolbar dimensions, page scale/rotation and error styling are unchanged.
- The gesture thresholds, programmatic-scroll exclusion, annotation/input/dialog/resize/selection guards and Tab/Escape recovery are unchanged. Hidden bars remain inert and become keyboard-accessible again on recovery.
- Added `test/android-reader-chrome.test.mjs`; updated the obsolete label assertion/double in `test/android-pdf-reading-only.test.mjs`. No recovery behavior assertion was removed.
- Updated `scripts/test_reader.mjs` and `scripts/test_reading_only_pdf.mjs` to assert absence of the overlay and actual gesture/keyboard recovery instead of clicking the deleted button. The former accepts `--source` to test current PDF code/CSS without rebuilding shared assets; the latter now also serves current CSS when bundling current source.
- No TXT implementation/test/script, Kotlin, dependency, version or publishing changes. `claim_files` was not available in this agent's tool list; exact source/test/script ownership was declared in the conversation before edits. Browser outputs are the two JSON reports and three prefixed PNG files below.

## Verification

1. `node --import ./scripts/upstream-loader.mjs --test test/android-reader-chrome.test.mjs test/android-recovery.test.mjs test/android-pdf-reading-only.test.mjs`: **31 passed, 0 failed**.
   - 13 chrome cases cover deliberate input, upward wheel/touch and keyboard recovery, inert restoration, horizontal/multi-touch/zoom/bottom-boundary exclusions, safety guards and source/CSS contracts.
2. `PLAYWRIGHT_CHANNEL=msedge node scripts/test_reader.mjs --source`: **passed**.
   - Real PDF.js; PDF and translation-pane touch collapse/reverse recovery; Tab/Escape restoration plus keyboard-only toolbar reachability; no reveal element; 411px viewport recovery; pinned/native selection and annotation protection; unchanged PDF.js viewport/scale/rotation/toolbar geometry; programmatic scrolling ignored.
   - Existing cache-only manual reopening, translation behavior and incremental annotation save/reopen assertions also passed. PDF/translation available-height gains remain 32px/33px.
   - Evidence: `pdf-chrome-android-reader-browser.json`; generated screenshots are local-only under `output/reader-interactions/pdf-chrome-reader-{hidden-controls,landscape,portrait}.png`, not committed release files.
3. `node scripts/test_reading_only_pdf.mjs`: **passed**, Edge `156.0.4314.58`.
   - No overlay; upward wheel and Tab/Escape restore controls and tab order; annotation protection; original-only isolation; real incremental save/reopen; successful mode-switch flush acknowledges reading then annotation save; injected save failure retains the document and restores input.
   - Evidence: `reading-only-pdf-browser.json`.
4. Script syntax checks: **passed** for both browser scripts and the new test.
5. PDF-only TypeScript check using the repository tsconfig/compiler options, excluding `text-*.ts` root files: **0 diagnostics**. `npm run check:reader` initially passed, but the later full rerun encountered concurrent TXT work: missing `./text-navigation` at `text-reader.ts:9` and implicit-any `page` at `text-reader.ts:281`. These files were not changed here; rerun the full check after that work settles.

## Limitations and next step

These are desktop Edge/Chromium tests with touch emulation and a simulated native bridge/storage/save host, not Android WebView, accessibility-service, system gesture or real-device tests. No real inference/model was used. The bilingual harness retains its pre-existing ignored resource 404; no other browser error occurred, and the original-only report has no errors.

Current PDF sources/CSS were bundled in memory against existing generated PDF.js assets. No shared asset rebuild, APK build, installation, cache/schema change or publication was performed. After all agents finish, rerun the full typecheck, regenerate/package assets once, and smoke-test PDF upward recovery and hardware-keyboard/accessibility recovery on Android. Tab restores toolbar availability without forcing an unsolicited focus jump.
