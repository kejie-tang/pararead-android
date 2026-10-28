ParaRead Android 0.2.0-alpha.9 — CPU instruction dispatch / CPU 指令优化

Fix a CPU build limitation found while investigating lower Qwen3 throughput than PocketPal: the ordinary ggml CPU operations were compiled for baseline ARM64, even though KleidiAI was enabled. Package upstream dotprod, dotprod + FP16, and dotprod + FP16 + i8mm plugins, and select one after its runtime feature check. Retain the baseline CPU plugin for older devices. SVE/SME plugins are not packaged.

- Record the selected CPU plugin in speed-test details and JSON. Keep the existing translation and pp512 → tg128 workloads, thread count, context and batch settings unchanged for comparison.
- Keep CPU as the default. Clarify that experimental Vulkan can be slower or cause UI stutter, and that fallback handles GPU failures rather than low performance.
- Preserve existing models, documents and validated translation caches.

针对 Qwen3 与 PocketPal 的速度差距，修复普通 CPU 算子只按基础 ARM64 编译的问题。新增 dotprod / FP16 / i8mm 内核，按设备能力自动选择，并保留旧设备回退；测速详情和导出结果显示实际 CPU 内核。建议先使用 CPU 复测相同模型与线程数。Vulkan 仍为实验选项，可能更慢或卡顿。

Android 9+ / ARM64, versionCode 9. Debug-signed preview using the same local certificate as alpha.8; install over alpha.8 to retain app data. APK, SHA256SUMS.txt and a notices ZIP are attached. Model weights and third-party papers are not bundled.

Validation and comparison: [CPU kernel investigation](https://github.com/kejie-tang/pararead-android/blob/main/docs/reports/cpu-kernel-dispatch.md). **No Dimensity 9400 throughput improvement is claimed until this build is retested on that device.**
