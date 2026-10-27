# ParaRead Android

**Read PDFs in two languages, side by side.**

Keep the original page in view, tap a sentence to follow its translation, and use on-device AI to work through papers and technical documents on your Android tablet.

[![Android CI](https://github.com/kejie-tang/pararead-android/actions/workflows/android.yml/badge.svg?branch=main)](https://github.com/kejie-tang/pararead-android/actions/workflows/android.yml) [![Android 9+](https://img.shields.io/badge/Android-9%2B-3DDC84?logo=android&logoColor=white)](#quick-start) [![Code license: Apache-2.0](https://img.shields.io/badge/Code-Apache--2.0-blue)](LICENSE)

**[Download preview APK](https://github.com/kejie-tang/pararead-android/releases/download/v0.2.0-alpha.5/ParaRead-0.2.0-alpha.5-android-arm64-debug.apk)** · [All releases](https://github.com/kejie-tang/pararead-android/releases) · [Quick start](#quick-start) · [简体中文](README.zh-CN.md)

An independent, unofficial Android adaptation of [ParaRead](https://github.com/Chuanyunux/pararead) 0.6.1. **Development preview:** ARM64 only; the app interface currently uses Chinese labels.

![Side-by-side PDF reading with a selected sentence linked to its translation panel](docs/reports/reader-landscape.png)

*Layout and interaction demo using the original sample guide in desktop Chromium with touch emulation. The panel shows labeled Mock output, not a real model translation or a physical-tablet capture.*

## Keep the context while you read

A translation is easier to check when the source is right beside it. ParaRead Android keeps the PDF, its figures, and its page layout visible while you follow the translated text.

| Feature | What you can do |
| --- | --- |
| Sentence alignment | Tap the source or translation to find the matching sentence, with highlighting, vertical alignment, and linked scrolling. |
| On-device translation | Run a supported Qwen2.5, Qwen3, Qwen3.5, or LFM2/2.5 GGUF locally through llama.cpp. After downloading the model, translate without a remote translation service. |
| Flexible reading pace | Translate a selected passage, the current page, nearby pages, or the whole document. Cancel when needed. |
| Saved progress | Reuse cached translations and reading positions. Whole-document jobs save a checkpoint after each page and can continue when reopened with matching settings. |
| Notes and PDF export | Keep local annotation drafts and save an annotated PDF copy. |
| Your translation setup | Search Hugging Face, choose a GGUF quantization, or download the default model; adjust CPU threads and terminology; optionally use an OpenAI-compatible API. |
| Test your device | Run a real-model speed test inside the app, compare translation and raw engine token/s, and export the results. |

## Quick start

**You need:** Android 9 / API 28 or newer, an ARM64 device, and an Android System WebView with `WebMessageListener` support. The layout is designed for tablets.

1. **Install the [preview APK](https://github.com/kejie-tang/pararead-android/releases/download/v0.2.0-alpha.5/ParaRead-0.2.0-alpha.5-android-arm64-debug.apk).** The current download is debug-signed (`0.2.0-alpha.5`). If upgrading from a differently signed build, you may need to uninstall first; export important annotated PDFs before uninstalling, which removes app data.
2. **Open a PDF.** Try **打开示例文档** (Open sample document), or import a PDF with selectable text through the system file picker.
3. **Download the local model.** Tap **下载默认模型** (Download default model), or open **设置 → 离线 GGUF** (Settings → Offline GGUF). The app checks the full file size and SHA-256 before enabling it.
4. **Start reading.** Tap a sentence, translate a page, or start a whole-document job. Change the target language in **目标语言** (Target language). After an explicit cancellation, use **全篇 / 继续** (Whole document / Continue) to resume.

### The local model

| | Current default |
| --- | --- |
| Model | Qwen3 1.7B, Q4_K_M, thinking disabled |
| Download | About **1.36 GB**, separate from the APK |
| Storage | About **2.8 GB free during download and verification**, plus space for PDFs |
| Runtime | llama.cpp on the CPU; automatic or manual thread count |
| Source | A fixed, SHA-256-verified GGUF layer from the Ollama registry; **no Ollama app required** |
| Model search / imports | Hugging Face search and single-file GGUF downloads; Qwen2.5, Qwen3, Qwen3.5 and LFM2/2.5 text templates |

**Choose another model:** open **设置 → 离线 GGUF → 从 Hugging Face 搜索下载**. Search a model name or enter `org/repo`, inspect its license, and choose a quantization. Downloads are pinned to a repository commit and checked against its LFS SHA-256. A successful download replaces the current local model. LFM2.5 is experimental: small-model formatting and translation completeness have known failures. See the [actual model compatibility results](docs/reports/model-compatibility.md).

**Want a smaller, faster model?** Try `Qwen3-0.6B-Q4_K_M.gguf` (**397 MB**) from [Unsloth](https://huggingface.co/unsloth/Qwen3-0.6B-GGUF). The official [Qwen Q8_0](https://huggingface.co/Qwen/Qwen3-0.6B-GGUF) is **639 MB**. These are candidates for fast preview reading; small models still miss details and can mistranslate numerical relations. See the [small-model tests, exact files and recommendations](docs/reports/small-model-evaluation.md).

Alpha.5 saves independently validated sentences and retries only failures, with a marker checklist and a final single-sentence text fallback. Missing or duplicated protected content is still rejected; recovery can still fail. See the [actual recovery results](docs/reports/format-recovery.md). Translation cache keys are versioned, so translations will be regenerated after upgrading. The default download remains Qwen3 1.7B.

Below 0.6B, a host-side LFM350 prototype now passes the format fixtures, but still reverses numerical meanings; Haidass143 is only partially repaired. These adapters are not enabled in the released app. See the [smaller-model repair experiments](docs/reports/tiny-model-recovery.md).

Downloads use Android's system queue, with progress, cancellation, retry, and monitoring after reopening the app. Removing the model keeps your documents, reading progress, and translation cache. See [model management](docs/reports/model-management.md) and [CPU thread settings](docs/reports/cpu-threads.md) for implementation and test details (Chinese).

### Where your text goes

- **Local mode:** translation runs on the device. The model download needs a network connection; local inference does not send source text to a remote translation service. There is no automatic fallback to a remote API.
- **API mode:** the selected sentences and translation prompt are sent to the endpoint you configure. Remote endpoints require HTTPS; local HTTP services are supported.
- **Mock mode:** explicitly labeled output for trying the interface and running tests. It does not evaluate translation quality.

Imported PDFs are copied to private app storage, alongside local reading state, drafts, and caches. Imports therefore need extra disk space. For API and local-server configuration, see the [backend guide](docs/local-llama.md) (Chinese).

## Try a paper you know

The app includes an original six-page [reading guide](samples/pararead-reading-guide.pdf), with two-column text, a formula, a table, and a vector diagram. Its text and artwork use Apache-2.0; see the [sample notice](samples/NOTICE).

For a familiar research paper, download Vaswani et al., *Attention Is All You Need* (2017), from its [official arXiv page](https://arxiv.org/abs/1706.03762) and import your copy. The repository, APK, and release assets do not include that paper. Its [arXiv distribution license](https://arxiv.org/licenses/nonexclusive-distrib/1.0/license.html) and copyright remain separate from this project's code license. Upstream PDF.js sample PDFs are excluded from Android assets.

## Speed test

**Test your own device inside the app:** open **设置 → 离线 GGUF → 速度测试** (Settings → Offline GGUF → Speed test), then tap **开始测速**. Download or import a model first. The test uses the CPU thread count currently entered in Settings, including edits you have not saved.

- Runs one fixed, original 158-word English-to-Chinese paragraph four times: once with a fresh model instance, then three times with the model loaded. The translation cache is bypassed.
- Shows first stream response, completion time, and the median of the three warm runs. Generation speed uses native token counts, including JSON formatting tokens, divided by generation time.
- Then measures three raw engine **pp512 / tg128** runs, excluding grammar, sampling and per-token UI callbacks. Reports decode, sampling/grammar and callback time separately.
- Supports cancellation and screen rotation. The latest complete result stays on your device and can be exported as JSON with the model hash, device, threads, engine version, and all four samples.

The reader pauses during testing and resumes after you close it. First-use timings include model loading; OS file caches are left intact. First response may be JSON syntax. Translation and raw engine results measure different workloads; neither establishes full-PDF throughput or translation quality. The new JSON sampler avoids a full vocabulary grammar scan for accepted candidate tokens, and ARM kernels use KleidiAI runtime dispatch. See the [PocketPal comparison and measured limits](docs/reports/model-compatibility.md). See [in-app test details](docs/reports/app-speed-test.md).

### Earlier reference measurements

Measured on **26 October 2026**, using the real **Qwen3 1.7B Q4_K_M** model, English-to-Chinese translation, thinking disabled, and CPU inference with a 4,096-token context. These are engine/integration timings; physical-tablet and WebView end-to-end latency are still unmeasured.

| Environment | Workload | First stream update | Completed | Samples |
| --- | --- | --- | --- | --- |
| M1 Pro host, 6 threads | 1 sentence, model initially unloaded | **1.70 s** | **2.92 s** | Median of 3 |
| M1 Pro host, 6 threads | 4 sentences, model already loaded | **0.68 s** | **5.42 s** | Median of 3 |
| Android ARM64 emulator, auto 6 threads | 2 sentences, model initially unloaded | 1.92 s | 9.79 s | 1 prior run |
| Android ARM64 emulator, auto 6 threads | 2 sentences, after cancellation, model loaded | 1.60 s | 9.31 s | 1 prior run |

The host is an Apple M1 Pro with 32 GiB RAM; the emulator has 8 vCPUs and 6 GiB RAM. Each Android batch includes **3 model calls**: an invalid batch followed by two single-sentence repairs. Workloads differ, so these rows are not a platform ranking. First stream updates can contain JSON syntax; they are not the first translated word or tokens/s. First-use timings include model loading, with OS file caches left intact.

<details>
<summary>Run the real-model speed test yourself</summary>

After setting up the [source-build toolchain](#build-from-source), point to your local copy of the default GGUF. The benchmark checks its SHA-256, starts three fresh JVM/provider sessions, and saves all observations plus the median and range. It does not download model weights.

```sh
export PARAREAD_GGUF="/absolute/path/Qwen3-1.7B-Q4_K_M.gguf"
python3 scripts/benchmark_translation.py --runs 3 --threads 6
```

Output: `artifacts/translation-speed/summary.json`, with per-run logs alongside it. Use `--threads 0` for the automatic thread policy. See [method and raw results](docs/benchmarks.md), [host observations](docs/reports/translation-speed-host.json), and the [Android reference](docs/reports/cpu-threads-android-real.json).

</details>

## Preview status

**A working development preview, with physical-tablet validation still ahead.** Evidence covers core and bridge tests, Android ARM64 emulator checks including real-model speed and format tests, and a Chromium reading/annotation round trip. Real Qwen3 inference has also passed smoke checks on the host and an Android ARM64 emulator. See the [verification record](docs/reports/alpha3-verification.json) and [public build checks](docs/reports/public-upload.md).

Physical-tablet performance, sustained real-model reading sessions, academic translation quality, process-kill/reboot recovery, accessibility, and stylus behavior still need validation. Synthetic 100/300/500 MiB PDF tests establish a limited baseline, not a complete real-document performance claim. This preview does not provide an OCR workflow for image-only scans, and legacy VS Code cache import is not implemented. Form-field changes above 512 pages are currently rejected; damaged or unusually complex PDFs may also hit explicit resource limits. See [compatibility](docs/compatibility.md), [performance](docs/performance.md), and the [task list](docs/tasks.md) for details (Chinese).

**Feedback from real tablets is especially useful.** [Open an issue](https://github.com/kejie-tang/pararead-android/issues) with your device, Android/WebView versions, backend/model, and reproduction steps. If sharing a PDF, use a small sample you have permission to distribute. Leave API keys and private documents out of reports.

## Build from source

Requirements: **Node.js 24+**, **Python 3.9+**, **JDK 21**, **Android SDK 36**, **NDK 28.2.13676358**, and **CMake 3.22.1**. Set `JAVA_HOME` and `ANDROID_HOME`, or configure Android Studio with JDK 21.

```sh
npm ci
python3 scripts/fetch_upstream.py
python3 scripts/fetch_llama.py
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

The source-fetch scripts verify pinned upstream Git blob hashes and the KleidiAI source archive SHA-256. Source snapshots live in the ignored `.references/` directory; model weights are not fetched by the build. `-PskipNative` produces a Mock-only prototype and cannot validate offline inference.

<details>
<summary>Run checks and find the technical docs</summary>

```sh
npm test
npm run check:reader
npm run test:android-web
npx playwright install chromium
npm run test:reader
./gradlew :core:test :app:lintDebug
```

Android instrumentation tests require an ARM64 device or emulator. The real-model tests need their documented model fixtures; see the reports before running the full suite. Large-PDF benchmark scripts also create substantial test data and use a connected debug device.

Most detailed engineering documents are currently in Chinese:

- [Architecture](docs/architecture.md) and [message protocol](docs/bridge-protocol.md).
- [Compatibility](docs/compatibility.md), [test records](docs/reports/alpha3-verification.json), and [performance](docs/performance.md).
- [Whole-document recovery](docs/reports/whole-document-recovery.md) and [remaining work](docs/tasks.md).

</details>

For newer debug builds, open [Android CI](https://github.com/kejie-tang/pararead-android/actions/workflows/android.yml) → a successful run → **Artifacts** → `pararead-android-debug-arm64`. Sign-in is required and artifacts are retained for 14 days. CI debug signatures can change between runs. Versioned APKs, checksums, and notice bundles belong in [Releases](https://github.com/kejie-tang/pararead-android/releases); see the [signing and release guide](docs/publishing.md) (Chinese).

## Credits and license

Built on [ParaRead](https://github.com/Chuanyunux/pararead) by Chuanyunux, whose upstream notices credit **vscode-pdf** by Mathematic Inc and **Mozilla PDF.js**. The TypeScript/PDF.js reader comes from ParaRead; this adaptation adds or modifies the Android host, file access, persistence, translation scheduling, model management, and JNI integration. Local inference uses [llama.cpp](https://github.com/ggml-org/llama.cpp) with Arm KleidiAI kernels and separately downloaded model weights.

Android-original code and adaptation changes use **[Apache License 2.0](LICENSE)**. Third-party code, fonts, WASM, and model materials retain their own licenses. Keep the [NOTICE](NOTICE), [third-party disclosures](THIRD_PARTY_NOTICES.md), and [license originals](third_party/) with distributions. The project license does not grant rights to imported PDFs or arbitrary models and translations.

This is an independent, unofficial project with no affiliation with or endorsement from the upstream projects or model providers. Names and trademarks belong to their respective owners. PocketPal, Readest, MJ PDF, and PocketLLM were behavioral or architectural references; their code and assets were not copied. See the [source and license review](docs/licenses.md) (Chinese).
