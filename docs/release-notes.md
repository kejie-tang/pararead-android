ParaRead Android 0.2.0-alpha.10 — reuse CPU workers / 复用 CPU 线程池

Fix another avoidable CPU cost found during the PocketPal comparison: ParaRead did not attach a persistent threadpool, so ggml created and joined worker threads for each compute graph. A model session now owns one pool shared by prefill and decode. The pool uses the selected CPU plugin and is detached, stopped and joined when its engine is freed, including GPU failure and CPU retry.

- Retain alpha.9's feature-checked dotprod / FP16 / i8mm CPU plugins.
- Keep the same model, six-thread comparison setting, context, batch sizes, KV types, automatic Flash Attention, sampling/grammar and benchmark workload. Keep normal priority, default hybrid polling and system CPU placement.
- Record the pool in speed-test details and exported JSON. Existing reports, models, documents and validated translation caches remain compatible.

进一步排查发现原生层每次计算图都会重复创建、销毁线程。此版改为模型会话内复用线程池，保持其余参数不变；取消后可继续使用，释放模型时回收所有工作线程。保留 alpha.9 的 CPU 指令优化。实验 Vulkan 在当前设备上没有稳定收益，阅读仍建议使用 CPU。

Android 9+ / ARM64, versionCode 10. Debug-signed preview using the same local certificate as alpha.9; install over alpha.8/9 to retain app data. Model weights and third-party papers are not bundled. APK, checksum file and retained notices are attached.

Comparison and validation: [CPU threadpool investigation](https://github.com/kejie-tang/pararead-android/blob/main/docs/reports/cpu-threadpool.md). Emulator improvements cannot be multiplied together to predict a phone's speed. **Dimensity 9400 performance still needs retesting on the device.**
