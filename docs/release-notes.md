# ParaRead Android 0.2.0-alpha.15 — 自动翻译当前页，单击选中 / automatic page translation, click-only selection

## 中文

- **修复跳页后没有自动翻译。** 进入此前作为邻页排队的页面，或快速跳走再返回时，当前页会接管旧视口任务；旧任务的迟到取消回复不会再让该页停住，无须点击句子才开始翻译。
- **单击选中，不悬停选中。** 原文和译文栏默认关闭 hover 选中，保留单击联动。自动翻译与选中独立：新用户默认“当前页”；已有用户的当前页、邻页或手动选择保留。设置页增加中英文说明。**若选“手动”，仍需点击句子或翻译操作。**
- **Qwen thinking 配置核对。** 本地 Qwen3/Qwen3.5 使用预先结束思考段的模板，API 请求发送 `enable_thinking=false`。新增实际 provider 调用路径测试，覆盖 CPU/Vulkan 与 JSON/text 输出。这次不改变已有提示词指纹或清空译文缓存。
- **保留 alpha.14 的全部功能。** 包括紧凑书库、继续阅读、重命名/删除、页码恢复，以及 alpha.13 的全屏滑动收起工具栏。没有用旧版代码覆盖这些更新。
- 改善 Windows 原生构建：支持便携 Clang 主机工具链，修复生成 Vulkan 工具链时 Ninja 路径的反斜杠转义。

## English

- Visible pages take ownership of cancelled/obsolete speculative work after a page jump, without requiring a sentence click. Late cancellation replies cannot clear the new request owner.
- Hover no longer selects sentences; clicking still does. Automatic translation is independent of selection. New installations default to Current page, while existing Current page / Nearby pages / Manual preferences are retained. Manual remains explicit.
- Verified thinking-off configuration for Qwen3/Qwen3.5 native prompts and `enable_thinking=false` in API requests, with provider-path regressions. Prompt fingerprints and saved translations are not invalidated.
- Retains alpha.14 library/resume/document actions/page restoration and alpha.13 full-screen auto-hiding controls.
- Adds Windows host Clang support and literal Ninja-path handling for the Vulkan shader tool.

## Validation / 验证边界

Regression coverage includes stale prefetch ownership, rapid page return, protecting click/whole-document work, click-only selection, PDF.js page jumps without selection, and the existing full-screen controls and annotation/cache scenarios. Native thinking checks use a fake runtime and API checks use a local HTTP test server; these are not real-GGUF inference, thinking-token or performance measurements. A third-party API endpoint must implement the requested thinking flag for it to take effect.

This release has no new physical-device, emulator or Android instrumentation run. Previous alpha.13/alpha.14 device reports remain historical evidence, not a claim of retesting this build. [Current validation details](https://github.com/kejie-tang/pararead-android/blob/main/docs/reports/alpha15.md).

## Install / 安装

Android 9+ / ARM64, **versionCode 15**. This preview retains the existing signing identity. Certificate SHA-256 must match before publication:

```text
e044c8f329f42465866add4d041f29c498009361241610c8ee2de43ae6e87a45
```

Install over the previous same-signed preview to retain private data. If installation fails, **do not uninstall to bypass the error**: uninstalling deletes private PDFs, translations and models. The retained certificate is the legacy Android Debug preview identity, not a newly introduced production identity. APK, notices and SHA-256 checksums are attached; no model weights or third-party papers are bundled.
