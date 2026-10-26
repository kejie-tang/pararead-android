# Translation speed test

This page documents the development reference measurements shown in the [English README](../README.md#speed-test) and [中文 README](../README.zh-CN.md#速度测试). The measurements describe real model generation through the project's own JNI/provider and queue code. They do not measure the time from a tap to a visible translated sentence in the Android reader.

## Configuration

| Setting | Value |
| --- | --- |
| Date | 2026-10-26 |
| Host | Apple M1 Pro, 10 logical CPUs, 32 GiB RAM, macOS 26.6.2, ARM64 |
| Host inference | 6 CPU threads, context 4,096, batch 512, microbatch 128; no Metal/BLAS/Accelerate |
| Model | Qwen3 1.7B Q4_K_M, thinking disabled, 1,359,279,776 bytes |
| Model SHA-256 | `3d0b790534fe4b79525fc3692950408dca41171676ed7e21db57af5c65ef6ab6` |
| Engine source | `llama.cpp@2280f0fd163b03d4331d49e08460f66502e35854` |
| Workload | English to Simplified Chinese; the exact inputs and outputs are retained in the raw JSON |
| Host repetitions | 3 separate JVM/provider runs; each executes the existing four-case real-model check |
| Android reference | Earlier API 36 ARM64 ranchu emulator, 8 vCPUs / 6 GiB RAM, automatic 6 threads, airplane mode; one observation per case |

The JNI library is compiled from the same wrapper source used by Android. The host's OS, scheduler, compiler, and hardware differ from Android. A host result is not an estimate of a tablet's performance.

## Definitions

- **First stream update:** monotonic elapsed time until the first provider delta. It includes prompt prefill; the first request also includes model loading. A delta may contain only JSON syntax. No token-per-second metric is derived from callback counts.
- **Completed:** elapsed time for the complete test case, including generation and output validation. Android queue timings also include its validation/repair calls. Model-file hashing, source fetching, Gradle builds, PDF import/rendering, and UI painting are outside these intervals.
- **Model initially unloaded:** a fresh JVM/provider with no model handle. OS file caches are not cleared. File hashing can warm the disk cache, so this must not be called a cold-disk or system-startup benchmark.
- **Model already loaded:** the four-sentence host case follows the first single-sentence case. It calls the provider directly; a translation-result cache does not serve the timed result.
- **Repetitions:** medians and observed ranges describe three samples only. No p95, sustained throughput, quality score, or speedup over prior runs is inferred.

## Host results

See [translation-speed-host.json](reports/translation-speed-host.json) for the source commit, configuration, all three complete integration reports, input sentences, generated text, and unrounded timings. The suite also checks queue/cache reuse and cancellation followed by successful generation; all 12 integration cases passed. Only the first two cases are summarized as latency measurements.

| Workload | First update, median | Completed, median | Completed, observed range | Samples |
| --- | --- | --- | --- | --- |
| 1 sentence, model initially unloaded | 1.70 s | 2.92 s | 2.81-2.98 s | 3 |
| 4 sentences, model already loaded | 0.68 s | 5.42 s | 5.28-5.71 s | 3 |

## Android emulator reference

| Case | Sentences | First stream update | Completed | Provider calls |
| --- | --- | --- | --- | --- |
| Model initially unloaded | 2 | 1.92 s | 9.79 s | 3 |
| After cancellation, model loaded | 2 | 1.60 s | 9.31 s | 3 |

These are the existing observations in [cpu-threads-android-real.json](reports/cpu-threads-android-real.json), not a new device run. The first batch used a protected placeholder from the wrong sentence, failed validation, and was followed by two single-sentence repair calls. All three calls count toward completion time. The second timed batch uses a fresh result cache, so it is not a cache-hit measurement. It follows a cancellation exercise and is not a standalone steady-state benchmark.

Host and emulator inputs differ (one/four sentences versus two). Neither is a physical Android tablet. See [the earlier thread-settings report](reports/cpu-threads.md) for original conditions and limitations. Formal tablet speed, sustained inference, thermal behavior, model-loaded PDF scrolling, and first visible translation remain unmeasured.

## Reproduce the host test

Set up the toolchain listed in the [README](../README.md#build-from-source). You need a local copy of the fixed default GGUF; the runner checks its SHA-256 and does not download weights.

```sh
export PARAREAD_GGUF="/absolute/path/Qwen3-1.7B-Q4_K_M.gguf"
python3 scripts/benchmark_translation.py --runs 3 --threads 6
```

The runner calls [check_local_llama.sh](../scripts/check_local_llama.sh), which verifies/fetches the pinned llama.cpp sources, builds the host library if required, and executes [HostLlamaCheck.kt](../tools/host-llama/src/main/kotlin/org/pararead/host/HostLlamaCheck.kt). Compilation and fetching are excluded from the Kotlin case timers. There is no network translation request in this JNI test.

The aggregate goes to `artifacts/translation-speed/summary.json`; each timestamped subdirectory contains per-run JSON and build/inference logs. Use `--output` for a different summary path, `--runs 1` for one repetition, or `--threads 0` for the project's automatic CPU thread policy. Actual effective threads are saved in every raw run. The runner returns a failure if an integration case fails, the model hash differs, or the engine/thread configuration changes between repetitions.

For Android, the opt-in [RealModelIntegrationTest](../app/src/androidTest/java/org/pararead/android/RealModelIntegrationTest.kt) accepts `ggufPath` and `cpuThreads` instrumentation arguments. Supply a readable, already-installed GGUF; a test skipped for a missing model is not a speed result. See the [local-engine guide](local-llama.md) for fixture setup.

## PDF loading is a separate measurement

Existing [100/300/500 MiB synthetic PDF results](reports/phase-4.md) were collected with the Mock backend, without a loaded translation model. Their first-page and page-jump timings are useful for the PDF path, but cannot be described as real-model translation speed. The broader [performance plan](performance.md) defines the remaining device, memory, and sustained-work matrix.
