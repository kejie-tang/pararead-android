ParaRead Android 0.2.0-alpha.6 — development preview / 开发预览版

Choose Qwen3-0.6B or Qwen3-1.7B directly in Model management. Keep both downloaded and switch offline without downloading again.

- Add two curated Q4_K_M model cards: Qwen3-0.6B (397 MB) for quick reading and Qwen3-1.7B (1.36 GB) for quality priority, with source/license links and download-space estimates.
- Retain downloaded and imported models in a local library. New installations activate automatically; other downloaded models remain available. Existing managed model files migrate in place.
- Show the active model by name on the home screen and in settings. Remove the misleading editable model-name field from offline mode.
- Retry the model that actually failed, including after reopening the app. Preserve background downloads, progress, cancellation and SHA-256 verification.
- Delete models individually with a confirmation showing the model name and space to be freed. PDFs, translations and reading progress remain available. Deleting the active model clears its selection; other saved models can be selected explicitly.

新增直接下载 0.6B / 1.7B 的模型卡片，支持同时保留、离线切换和分别删除；修复失败重试下载错模型的问题，并明确显示当前使用的模型。旧版本的已安装模型原地迁移，无需重新下载。

Android 9+ / ARM64. The preview APK is debug-signed, versionCode 6. It uses the same local debug certificate as alpha.5. Differently signed builds require exporting important annotated PDFs before uninstalling (uninstall removes app data). APK, SHA256SUMS.txt and a notices ZIP are attached. Model weights and third-party papers are not bundled.

The default model remains Qwen3-1.7B. Qwen3-0.6B is for quick preview; both can omit or mistranslate details. RWKV and specialized NanoTranslator repair adapters remain research-only and are not enabled in the app. Emulator tests do not establish physical Dimensity 9400 performance.
