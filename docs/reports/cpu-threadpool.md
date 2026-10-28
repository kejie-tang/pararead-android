# Persistent CPU workers — alpha.10

One further avoidable cost was confirmed after alpha.9: ParaRead did not attach a persistent CPU threadpool. The pinned ggml implementation consequently created and joined worker threads for each compute graph. Alpha.10 creates one pool per loaded engine and reuses it across prefill, token decoding and subsequent requests. A small emulator comparison finds another approximately 11% improvement in 1.7B tg128. This is development evidence, not a Dimensity 9400 speed prediction.

## Why 26% is not a phone performance ceiling

The user's arithmetic is correct: 14 × 1.26 = 17.64 token/s, still 11.8% below 20; getting from 17.64 to 20 would require another 13.4%. However, the earlier 26% came from an Android emulator comparison of alpha.8 and alpha.9. It has not been measured on the user's phone. Neither that percentage nor the additional pool result below can be multiplied together to promise a physical-device rate.

The target remains the user's reported Dimensity 9400, CPU / six threads, Qwen3 1.7B Q4_K_M and PocketPal v1.20.1 (152). The exact GGUF hashes, installed ParaRead version, PocketPal context settings and thermal conditions of the original phone measurements remain unavailable.

## Confirmed source path and fix

- In pinned llama.cpp, [`llama_context`](https://github.com/ggml-org/llama.cpp/blob/2280f0fd163b03d4331d49e08460f66502e35854/src/llama-context.cpp) passes its attached pool to the CPU backend. Our wrapper previously never attached one.
- [`ggml_graph_compute`](https://github.com/ggml-org/llama.cpp/blob/2280f0fd163b03d4331d49e08460f66502e35854/ggml/src/ggml-cpu/ggml-cpu.c) creates a disposable pool when the plan has none, then frees it at the end of the graph. That repeats during one-token decode.
- PocketPal's declared llama.rn v0.13.0-rc.7 calls `common_init_from_params` in [`rn-llama.cpp`](https://github.com/mybigday/llama.rn/blob/v0.13.0-rc.7/cpp/rn-llama.cpp). Its [`common_threadpools::init`](https://github.com/mybigday/llama.rn/blob/v0.13.0-rc.7/vendor/llama.cpp/common/common.cpp) creates and attaches persistent pools.

ParaRead now resolves pool creation and destruction through the already-selected CPU backend registry. It does not directly link another CPU plugin. Prefill and decode use the same configured thread count, so they share a pool. Initial workers start paused; scheduling priority, polling (50) and CPU placement retain upstream defaults. On engine destruction the context is detached, workers are stopped and joined, and then the context and model are freed. Error cleanup and a Vulkan-to-CPU retry use the same engine destruction path.

Speed-test details show **CPU 线程池：复用**. Exported JSON includes `environment.cpuThreadpool`; `environment.cpuPlugin` continues to contain only the plugin name. The diagnostic engine string adds `cpu-pool-v1`. Translation cache identity is unchanged.

## Emulator comparison

Apple M1 Pro host; Android API 36 ARM64 emulator; 8 vCPUs / 6 GiB; six inference threads. All runs select `libggml-cpu-android_armv8.2_2.so` (dotprod + vector FP16). No compatible physical Android device is connected, and the i8mm plugin has not been executed here.

The same native benchmark performs a 16-token warmup, pp512 and then tg128 retaining the prompt context, repeated three times. It excludes sampling, grammar and per-token Java callbacks. Each test also validates a short actual Chinese translation. Context 4096, batch 512, microbatch 128, F16 KV, automatic Flash Attention, mmap, KleidiAI and the upstream revision remain unchanged. No CPU-heavy build runs concurrently with the comparison sessions; file caches remain warm. Host frequency and thermals are not controlled.

For 1.7B, run order is A → B → B → A. A is the published alpha.9 APK. B is a diagnostic alpha.9 build with only the native pool change; its engine diagnostic string predates the final `cpu-pool-v1` marker. Final alpha.10 retains that pool implementation and adds version/report/UI metadata. Both builds use the same test APK for the four sessions. Every sample, including the slower B1 result, is retained below and in the linked raw records.

| Run / Qwen3 1.7B Q4_K_M | tg128 samples (token/s) | Median pp512 | Median tg128 | Raw record |
| --- | --- | ---: | ---: | --- |
| A1 / alpha.9 | 39.55, 41.08, 43.43 | 249.74 | 41.08 | [JSON](cpu-pool-alpha9-a1.json) |
| B1 / persistent pool | 47.25, 36.54, 46.48 | 236.50 | 46.48 | [JSON](cpu-pool-b1.json) |
| B2 / persistent pool | 41.93, 47.67, 45.84 | 257.68 | 45.84 | [JSON](cpu-pool-b2.json) |
| A2 / alpha.9 | 40.81, 42.19, 43.72 | 254.18 | 42.19 | [JSON](cpu-pool-alpha9-a2.json) |

The median of all six A decode samples is **41.64**, versus **46.16** for B: **+10.9%**. The distributions overlap and include outliers; this small experiment does not establish a guaranteed uplift. Prompt processing does not show a consistent gain in this 1.7B comparison, so the change is not presented as an across-the-board improvement.

The exact 1.7B file is 1,359,279,776 bytes, SHA-256 `3d0b790534fe4b79525fc3692950408dca41171676ed7e21db57af5c65ef6ab6`.

A separate 0.6B check uses one alpha.9 session followed by one final alpha.10 session, with three measurements in each. It is less controlled than the 1.7B order:

| Qwen3 0.6B Q4_K_M | tg128 samples (token/s) | Median pp512 | Median tg128 | Raw record |
| --- | --- | ---: | ---: | --- |
| Alpha.9 | 72.70, 78.93, 83.77 | 490.30 | 78.93 | [JSON](cpu-pool-alpha9-06.json) |
| Alpha.10 | 98.93, 99.10, 100.16 | 525.82 | 99.10 | [JSON](cpu-pool-alpha10-06.json) |

That is +25.5% in this decode check, not an estimate for the phone. The 0.6B file is 396,705,472 bytes, SHA-256 `ac2d97712095a558e31573f62f466a3f9d93990898b0ec79d7c974c1780d524a`.

## Other obvious candidates checked

| Candidate | Finding / decision |
| --- | --- |
| Release compiler optimization | Android native code already uses Release / `-O3` / `NDEBUG`, including the debug-signed APK. A release signature alone does not unlock a missing optimization. |
| CPU instruction selection | Alpha.9 already adds feature-checked dotprod / FP16 / i8mm plugins. Retained unchanged; see [the first investigation](cpu-kernel-dispatch.md). |
| Flash Attention / KV cache | Ordinary inference already uses automatic Flash Attention and F16 K/V. The diagnostic F32 / Flash-off path is opt-in. PocketPal's Android defaults disable Flash Attention; blindly copying that default is not an established win. |
| Benchmark application overhead | Native tg128 does not run JSON grammar, samplers or per-token Java callbacks. The final long-translation report also records actual sampling/decode/callback times. |
| Context and batch sizes | PocketPal defaults differ (context 2048 / microbatch 512, versus 4096 / 128 here). Changing these changes capacity or prefill behavior; no physical-device evidence currently supports changing them to close the decode gap. |
| CPU affinity / priority | llama.rn selects high-frequency cores and enables a strict CPU mask. ParaRead retains OS placement and normal priority. Dimensity's heterogeneous cores make this a possible remaining difference, but benefit, thermals and UI responsiveness require device testing. No untested binding is introduced. |
| ThinLTO / other compiler flags | llama.rn has additional flags. There is no isolated result establishing a large remaining benefit; this change does not add speculative flags or alter numerical behavior. |
| Vulkan | The user reported similar or slower throughput with UI stutter. CPU remains the reading recommendation. The emulator verifies failure fallback only. |

PocketPal defaults were checked in its tagged [`contextInitParamsVersions.ts`](https://github.com/a-ghorbani/pocketpal-ai/blob/v1.20.1/src/utils/contextInitParamsVersions.ts); llama.rn's CPU selection is in [`JSIParams.cpp`](https://github.com/mybigday/llama.rn/blob/v0.13.0-rc.7/cpp/jsi/JSIParams.cpp). Defaults do not establish the user's actual settings.

After the CPU plugin and persistent-pool fixes, no further **demonstrated large gain** has been identified. Leave further tuning here unless a same-file, same-thread phone retest shows a material remaining gap. Small residual differences alone do not justify adding core-binding complexity or sacrificing responsiveness.

## Validation

- **22/22 Android regression tests**, 83.413 s: model library/catalog/download management, CPU/backend settings, reader integration, long native translation/benchmark UI, metadata and new pool label, rotation, cancellation, report persistence and Vulkan failure → CPU recovery. [Log](cpu-pool-android-tests.txt), [full speed report](cpu-pool-app-speed.json), [fallback record](cpu-pool-vulkan-fallback.json).
- **One dedicated real-native lifecycle test**, 2.578 s: exactly five persistent workers plus the calling thread for a six-thread pool; same worker IDs across separate benchmarks; **0 CPU ticks during a 500 ms idle window** after the polling window; cancellation interrupts work; the same pool runs another benchmark; all workers disappear after engine free. This verifies one idle window, not battery consumption over time. [Result](cpu-pool-lifecycle.json), [log](cpu-pool-lifecycle-test.txt).
- Six model/build comparison tests passed, with three engine samples each. Their adjacent `*-test.txt` logs are retained.
- Host JNI build and **4/4 real-model regression cases** passed: single/batch translation, protected content, queue repair/cache reopen, cancellation and another inference. This exercises the static CPU backend's pool API too. [Result](cpu-pool-host-regression.json).
- Core **44/44**, engine **8/8**, source contracts **16/16** passed. APK/test assembly and lint passed: **0 errors / 41 warnings**. [Build](cpu-pool-build.txt), [source tests](cpu-pool-source-tests.txt).
- Packaging checks: intended four CPU plugins and optional Vulkan backend; ARM64 / 16 KiB ELF and ZIP alignment; retained notices; no bundled model weights; original sample PDF only; unchanged signing certificate. [Hashes, sizes and counts](alpha10-verification.json).

Install alpha.10 over the same-signed preview, select **CPU / 6 threads**, retain the same 1.7B Q4_K_M file, let the phone cool and repeat the existing speed test. Exported JSON retains the model hash, plugin and pool selection. Overwrite installation retains the downloaded models, documents and validated translations. Reaching PocketPal's approximately 20 token/s remains unverified.

To reproduce the opt-in worker lifecycle check with a local GGUF already on a test device:

```sh
adb shell am instrument -w -r \
  -e class org.pararead.android.CpuThreadpoolTest \
  -e poolGgufPath /data/user/0/org.pararead.android/files/cpu-pool.gguf \
  org.pararead.android.test/androidx.test.runner.AndroidJUnitRunner
```

It writes `files/cpu-pool-lifecycle.json` and skips without `poolGgufPath`. Run instrumentation sessions sequentially.
