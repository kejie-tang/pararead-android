ParaRead Android 0.2.0-alpha.11 — lean downloads and bilingual menus / 下载省空间与双语菜单

Model downloads now write directly into private model storage, verify GGUF, file size and SHA-256, then rename the same file into place. A second full weight copy is no longer needed: a new Qwen3-1.7B download needs about **1.43 GB** free, including the 64 MiB reserve; 0.6B needs about **464 MB**. Background work and HTTP Range resume retain progress after network interruptions and process restarts. Previously saved models are retained; old in-flight system downloads finish through the legacy path.

- **Settings → Interface language:** choose System, 简体中文 or English. Native menus change immediately and the choice persists. Document source and translation target languages stay independent; the open reader is retained. The embedded PDF viewer and raw third-party diagnostics may retain their own language.
- **English-default and Chinese READMEs:** clearer reading features, setup, model choices, space requirements, privacy, screenshots and license disclosures.
- **Original launcher icon:** two facing pages with aligned lines, including adaptive and themed monochrome variants. Original artwork is Apache-2.0.

模型直接下载到私有模型目录的临时文件，完成校验后原子重命名，无须再复制一整份权重。支持后台下载、网络重试和断点续传；本次验证包含实际停止 App 进程后重新打开恢复下载。

在 **设置 → 界面语言** 中可选跟随系统、简体中文或 English，即时生效并记住选择，不改变文档源语言、翻译目标或译文缓存。同步重写中英文 README，并加入原创双页书本图标。

Validation: 52 core tests, 8 engine tests and 16 source-contract tests passed. Android API 36 / ARM64 suite: **24 passed, 1 optional live Hugging Face test skipped**; two stronger locale checks passed after the final dialog-language fix. A separate two-phase process-stop/resume check passed. Lint: 0 errors, 44 warnings. [Checks, evidence and limits](https://github.com/kejie-tang/pararead-android/blob/main/docs/reports/alpha11.md).

Android 9+ / ARM64, versionCode 11. Debug-signed preview with the same local certificate as alpha.8–alpha.10: install over those versions to retain documents, translations and models. APK, checksum file and retained notices are attached. Model weights and third-party papers are not bundled. Physical-device and manufacturer background-policy validation remain open; the CPU inference implementation is unchanged from alpha.10.
