# CPU kernel dispatch — alpha.9

## Device report that triggered this change

The user reported the following rounded rates on 28 October 2026. Their previously supplied configuration was Dimensity 9400, 6 CPU threads, and PocketPal v1.20.1 (152). The latest report did not include JSON exports, file hashes, temperature/thermal-state logs, or the installed ParaRead version; these observations are not a controlled benchmark.

| Model / requested backend | Translation token/s | Engine tg128 token/s |
| --- | ---: | ---: |
| Qwen3 1.7B / CPU | 16 | 14 |
| Qwen3 0.6B / CPU | 33 | 29 |
| Vulkan, same conversation context | 13 | 16 |
| PocketPal comparison, Qwen3 1.7B / CPU | — | about 20 |

The user also reported UI stutter with Vulkan. CPU remains the recommendation for reading on this device. A valid but slower GPU session does not automatically fall back: fallback detects initialization or inference failure, not low speed or frame rate.

## Confirmed build limitation

Alpha.8 sets `GGML_NATIVE=OFF`, leaves `GGML_CPU_ARM_ARCH` empty and `GGML_CPU_ALL_VARIANTS` disabled. Its ordinary CPU operations therefore compile for the default ARM64 target, without dotprod, vector FP16 or i8mm. The actual generated compiler commands confirm this. `-O3` and `-DNDEBUG` are already enabled, even in the preview APK: simply replacing the debug signature/build label is not the missing optimization.

KleidiAI is already enabled and has its own runtime-selected kernels. It does not imply that every ordinary ggml quantization, attention or other operation receives the same instruction optimization. This is a concrete build limitation; it does not prove that it explains the entire reported 14 → 20 token/s difference.

PocketPal v1.20.1 declares `llama.rn@0.13.0-rc.7` in its [package manifest](https://github.com/a-ghorbani/pocketpal-ai/blob/v1.20.1/package.json). That version [builds separate Android CPU variants](https://github.com/mybigday/llama.rn/blob/v0.13.0-rc.7/android/src/main/rnllama/CMakeLists.txt), including dotprod/i8mm, and uses ThinLTO. Its [benchmark implementation](https://github.com/mybigday/llama.rn/blob/v0.13.0-rc.7/cpp/rn-completion.cpp) performs a 16-token warmup, prompt processing, then token generation with that prompt retained. Our pp512 → tg128 follows the same broad sequence. The suspicion that PocketPal's benchmark starts tg128 with an empty context was **not confirmed**. Exact context/batch/flash-attention settings and averaging still must match before comparing scores.

Our tg128 path calls native decode directly, without a sampler, JSON grammar or per-token Java/Kotlin callback. JSON-format validation cannot by itself explain a low pure-engine tg128 result. Translation and tg128 use different token sequences and context lengths; tg128 also runs after four translations, when thermal conditions may have changed. This is a possible contributor, not a measured cause.

## Change

Use the pinned upstream `GGML_CPU_ALL_VARIANTS` machinery, but build/package only four NEON CPU plugins:

| Packaged plugin | Compilation target |
| --- | --- |
| `libggml-cpu.so` | ARMv8-A baseline |
| `libggml-cpu-android_armv8.2_1.so` | ARMv8.2-A + dotprod |
| `libggml-cpu-android_armv8.2_2.so` | ARMv8.2-A + dotprod + vector FP16 |
| `libggml-cpu-android_armv8.6_1.so` | ARMv8.6-A + dotprod + vector FP16 + i8mm |

Try the most capable plugin first and stop after the first successful load. Upstream `ggml_backend_load()` checks the plugin's feature score before initializing it. The score routine is compiled for the baseline ISA with LTO disabled; missing required features return zero. An older device can skip unsupported plugins and retain a CPU path. No SVE/SME plugin is packaged or probed. CPU selection never loads the Vulkan plugin.

The CPU choice appears in **Speed test → Detailed data** and exported `environment.cpuPlugin`; `systemInfo` includes `CPU_PLUGIN=...`. No manual kernel switch is required. Existing reports without the field still load. The upstream revision, KleidiAI dependency, model files, six-thread setting, context 4096 / batch 512 / microbatch 128, translation prompt/grammar, and benchmark workload remain unchanged.

## Emulator comparison

Apple M1 Pro host, Android API 36 ARM64 emulator, 8 vCPUs / 6 GiB RAM, 6 inference threads. One session per model/build, median of three engine measurements within each session. Run order: alpha.8 0.6B, alpha.8 1.7B, alpha.9 0.6B, alpha.9 1.7B. The same test APK, llama.cpp revision, GGUF files and benchmark parameters are used; a short validated translation precedes each three-run engine measurement. OS file caches are retained. Host frequency and thermals are not controlled. These observations are development checks, not a statistically established speedup.

| Model | Alpha.8 pp512 | Alpha.9 pp512 | Alpha.8 tg128 | Alpha.9 tg128 |
| --- | ---: | ---: | ---: | ---: |
| Qwen3 0.6B Q4_K_M | 240.6 | 476.8 | 68.8 | 83.1 |
| Qwen3 1.7B Q4_K_M | 91.4 | 262.8 | 32.9 | 41.5 |

Rates are token/s. Alpha.9 actually selects `libggml-cpu-android_armv8.2_2.so` on this device. The unsupported i8mm plugin is skipped, and native translation plus three benchmark runs complete without an illegal-instruction crash. Runtime `systemInfo` changes from baseline NEON to `FP16_VA = 1 | DOTPROD = 1`. The packaged i8mm library contains the requested optimized instructions, but has not been executed on compatible physical hardware. [Generated compiler-flag evidence](cpu-kernel-build-flags.json) also verifies that all four feature-score routines remain baseline-compiled with LTO disabled.

## Validation and limits

All 22 selected Android tests passed (106.258 seconds): model management/catalog/library, CPU/backend settings, reader integration, actual long-translation speed UI with native metrics, rotation, cancellation, persistence, the new CPU-kernel field, and real-GGUF Vulkan fallback followed by another CPU session. The emulator's GPU remains unsupported; no working Vulkan hardware path is claimed. [Android log](cpu-kernel-android-tests.txt), [complete saved speed report](cpu-kernel-app-speed.json), [Vulkan fallback result](cpu-kernel-vulkan-fallback.json).

Core 44/44, engine 8/8 and source-contract 16/16 tests passed. Debug assembly and lint passed (0 errors / 41 warnings). [Build log](cpu-kernel-build.txt). Packaging verified exactly the four intended CPU plugins, the optional Vulkan backend and other required native libraries; ARM64 / 16 KiB ELF and ZIP alignment; all retained notices; no bundled GGUF; only the original sample PDF; and the same signing certificate as alpha.8. [Artifact hashes and check counts](alpha9-verification.json).

See the adjacent `cpu-kernel-*.json` files and `cpu-kernel-*.txt` logs for the actual Android checks and build evidence. Emulator observations are only development validation, not Dimensity 9400 performance estimates. No physical device is connected to this development machine. The i8mm path must still be exercised on compatible hardware.

For a device comparison, install alpha.9 over alpha.8, select CPU and the same 1.7B Q4_K_M file, keep 6 threads, let the device cool, then repeat the same test. Export the report to retain the selected CPU plugin and model hash. Check 0.6B separately. The instruction fix is implemented; reaching PocketPal's approximately 20 token/s remains unverified.

Reproduce the opt-in CPU check with a readable GGUF already on a test device:

```sh
adb shell am instrument -w -r \
  -e class org.pararead.android.CpuKernelBenchmarkTest \
  -e cpuGgufPath /data/user/0/org.pararead.android/files/models/speed-test.gguf \
  -e cpuThreads 6 \
  org.pararead.android.test/androidx.test.runner.AndroidJUnitRunner
```

The test hashes the actual file outside the timers, validates a real Chinese translation through the public provider, then runs the existing pp512 → tg128 benchmark three times. It writes `files/cpu-kernel-benchmark.json`. Without `cpuGgufPath`, it is skipped. `expectedCpuPlugin` optionally asserts a specific plugin on a known device. Run instrumentation sessions sequentially.
