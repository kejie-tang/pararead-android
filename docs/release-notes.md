ParaRead Android 0.2.0-alpha.8 — experimental Vulkan GPU / Vulkan 实验加速

Add optional Vulkan GPU inference while retaining CPU as the default. Open Settings → Translation engine → Inference acceleration → Vulkan GPU（实验）, then save or run Speed Test with that selection.

- Load the Vulkan plugin only when requested. Reuse existing supported GGUF files; no model conversion or additional model download is needed.
- If GPU initialization, allocation or inference fails, release its context and retry the prompt once on CPU. Clear partial GPU drafts before retrying; cancellation does not trigger a retry.
- Show the requested/actual backend, GPU device and fallback reason in the reader or speed report. Export the same metadata in JSON. Discard tests that mix backends between samples or fail during a GPU engine benchmark.
- Recover to CPU on the next launch if a GPU session ended unexpectedly, including a native driver crash. Native crashes cannot be recovered within the same process.
- Wait for old native sessions to release memory before another provider loads, including across activity recreation.

新增 Vulkan GPU 实验选项：**设置 → 翻译引擎 → 推理加速**，选择后保存或直接测速。默认仍为 CPU。GPU 不可用或计算失败时重试 CPU，清除半截草稿；测速显示实际后端和回退原因，避免把 CPU 成绩当成 GPU 成绩。若驱动导致应用退出，下次启动恢复 CPU，可手动重新开启 Vulkan。

Android 9+ / ARM64, versionCode 8. Debug-signed preview using the same local certificate as alpha.7; install over alpha.7 to retain app data. APK, SHA256SUMS.txt and a notices ZIP are attached. Model weights and third-party papers are not bundled. Validated translation caches remain compatible.

Validation: ARM64 Android emulator, real Qwen3 GGUFs, unit and UI regression checks. The emulator's Vulkan device is unsupported, so real-model tests verify CPU fallback rather than hardware GPU throughput. **No Dimensity 9400 GPU speedup is claimed; physical-device Vulkan inference and performance still need testing.** This does not enable MediaTek NPU acceleration.

Implementation, usage and limitations: [Vulkan guide](https://github.com/kejie-tang/pararead-android/blob/main/docs/vulkan.md).
