# ParaRead Android 0.2.0-alpha.16 — 书库全部翻译与英文照抄校验 / library pretranslation and source-copy validation

## 中文

- **书库竖向菜单新增“全部翻译”。** 无须打开阅读界面，逐个文档在后台提取、翻译并逐页保存；书库显示排队、已处理页数、暂停和失败状态，可取消、继续或重试。打开文档或模型/设置界面会先停止后台任务并等待模型释放，不改变预翻译文档的阅读位置和最近阅读排序。
- **修复英文原文被当成中文译文接受、缓存及恢复的校验缺口。** JSON、纯文本、长句片段和旧缓存共用质量检查；不合格结果进入有限重试，正常缓存保留，不清空数据库或整体更换缓存指纹。公式、URL、代码、数字和短专名可原样保留。此检查不保证语义完整或翻译准确。
- **完成不等于全部成功。** 含失败句的页面会计入失败页，不能显示“全部翻译完成”；处理结束后可重试补译。旧版全篇进度首次继续时会重新扫描缓存，避免把旧英文照抄当作已完成；有效译文不会因此重新推理。
- **补齐后台可靠性。** 分页落库和逐页任务进度均等待确认；加入前后台独占交接、可等待的取消清理、进度写入所有权检查、跨数据库连接进度刷新和错误出口。
- **Ollama thinking 关闭更明确。** 常规 Ollama 端口的兼容 API 发送 `reasoning_effort=none`；其他兼容服务保留既有参数，不凭 Qwen 模型别名添加 Ollama 专用设置。
- 保留 alpha.15 的跳页自动翻译、单击选中、手动模式缓存恢复，以及书库管理、阅读位置恢复和全屏工具栏交互。

## English

- Library document menus now offer **Translate all**, without entering the reader. Durable per-page jobs show queued/running/paused/partial-failure progress, with cancel/resume/retry. Foreground reading and model operations wait for background resources to be released. Pretranslation does not change reading position or recent-reading order.
- Chinese-target output and cache reads reject ordinary English copying through shared JSON/text/fragment validation and bounded recovery. Good caches and fingerprints are retained. Equations, URLs, code and short proper names can remain unchanged; this is not a semantic-accuracy guarantee.
- Failed pages are reported as partial completion, never full translation success. Legacy whole-document cursors are rechecked once using their valid sentence caches.
- Adds page-save/checkpoint acknowledgements, exclusive resource handoff, joined cancellation, job-write fencing, cross-connection library invalidation and fatal-error exits.
- Standard-port Ollama API requests explicitly disable reasoning with `reasoning_effort=none`; Qwen model aliases alone do not change unrelated compatible servers.
- Retains alpha.15 reader interactions, library management, reading restoration and full-screen controls.

## Validation / 验证边界

Real local **qwen3:0.6b** testing used two short original sentences, **CPU / two threads / 1024-token context / thinking off**, after the user explicitly approved this fallback. The translation queue repaired an English cache entry in one model request, preserved a good Chinese entry and reopened with no further inference. GPU testing was blocked by the host driver; it was not silently replaced by CPU before permission.

A real desktop Edge/PDF.js regression uses an **869-byte, two-page generated PDF** to check headless extraction, page/checkpoint barriers, reload/resume and no viewer/canvas/reading-history writes. Its Native bridge, durable storage and translation responses are simulated. JVM tests, TypeScript/Web regressions, native APK/test-APK compilation, Lint, signatures and alignment are checked separately. [Detailed evidence](https://github.com/kejie-tang/pararead-android/blob/main/docs/reports/alpha16.md).

**No Android physical-device, emulator or instrumentation execution was performed for this version.** In particular, desktop headless tests do not establish detached Android WebView/Worker behavior. No real Qwen3:1.7b or whole-paper inference reproduction is claimed. Android foreground-service quotas and OEM restrictions may interrupt long jobs; resume from the library menu.

## Install / 安装

Android 9+ / ARM64, **versionCode 16**. The signed release must retain certificate SHA-256:

```text
e044c8f329f42465866add4d041f29c498009361241610c8ee2de43ae6e87a45
```

Install over the previous same-signed preview to retain data. **Do not uninstall to bypass an upgrade/signature error**: uninstalling deletes private PDFs, translations and models. This is the retained legacy Android Debug preview identity, not a new production identity. APK, notices and SHA-256 checksums are attached; model weights and third-party papers are not bundled.
