ParaRead Android 0.2.0-alpha.3 — development preview / 开发预览版

- Add Hugging Face model search, quantization selection and downloads pinned to a commit and LFS SHA-256. Display model licenses; a verified selection replaces the current local model.
- Support text-only Qwen3.5 and LFM2.5 templates alongside Qwen3. LFM2.5 remains experimental: protected-token and translation-completeness failures are documented in the model report.
- Replace full-vocabulary grammar filtering on every token with proposal validation and grammar-filtered resampling when required. Constrain output IDs/count to the input batch; retain strict placeholder validation.
- Enable runtime-dispatched Arm KleidiAI CPU kernels, with original license texts bundled.
- Expand the in-app test to a 158-word translation and three pp512 → tg128 engine runs, with decode/sampling/callback timing and JSON export.

新增 Hugging Face 搜索下载、多模型文本模板、ARM 内核与约束采样优化；测速同时显示长段翻译和纯引擎成绩。LFM 小模型的格式与漏译问题没有作为通过隐藏，详见仓库实测报告。

Android 9+ / ARM64. This release's `*-debug.apk` uses the same local debug certificate as alpha.2 and versionCode 3. Differently signed builds require exporting important annotated PDFs before uninstalling (uninstall removes app data). APK, SHA256SUMS.txt and a notices ZIP are attached. Model weights and third-party papers are not bundled.

Host and Android ARM64 emulator observations do not establish physical Dimensity 9400 performance. The reported PocketPal v1.20.1 tg128 result and ParaRead's old JSON translation rate measured different workloads; use the new raw tg128 result for a closer on-device comparison. Translation quality still requires checking against the original.
