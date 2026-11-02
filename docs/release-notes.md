# ParaRead Android 0.2.0-alpha.18 — 清爽暂停提示与明确翻译错误 / Quiet pauses and inline translation errors

## 中文

- **暂停提示不再常驻。** 书库中无错误、无需复核的闲置“已暂停，可继续，*/*页”行约 **2.5 秒**后收起，连同进度条和空白间距一起隐藏。只改变显示，不删除任务或已保存的页进度；仍可从文档菜单“继续全部翻译”。恢复排队、开始翻译或出现新错误时，会取消旧计时器并显示当前状态。
- **翻译失败不再伪装成英文译文。** 某句最终失败时，译文位置直接显示失败原因及点击重试提示，不再退回英文原句。错误采用明确样式，长错误码可换行，浅色/深色模式均可读；诊断文字按纯文本显示。提示及第三方错误码可能保留原语言。
- **重试不影响好译文。** 重试成功后，错误被正常译文替换；相邻已完成译文继续保留。生成中的中文仍是草稿，必须通过完整校验和持久化才能成为成功译文；不把不合格草稿强行当成翻译成功。正常等待、主动取消和切页取消保持原行为，不误报失败。
- 保留 alpha.17 的当前页优先自动翻译、有效缓存复用和取消提示超时，以及后台全篇翻译、阅读位置、批注和全屏行为。未修改模型引擎、提示词、缓存指纹或数据库结构。

## English

- The library's idle, healthy paused-progress row dismisses after about **2.5 seconds**, including its bar/spacing. The durable cursor and document-menu resume action remain. Queued/running work, errors, failed pages and review warnings do not expire; a changed job/state cancels the old timer.
- A failed sentence now shows its failure reason and retry hint directly in the translation pane, instead of reverting to the English source. Long diagnostics wrap, light/dark error colors remain readable, and diagnostic content is rendered as text rather than HTML. Existing localization and raw diagnostic language are preserved.
- A successful retry replaces the error without resubmitting accepted neighbours. Drafts still require complete validation/persistence; unvalidated Chinese drafts are not promoted to success. Normal waiting and cancellation behavior is unchanged.
- Retains alpha.17 current-page priority, cache reuse, background jobs, reading restoration, annotations and fullscreen behavior. No engine, prompt, cache-fingerprint or database-schema change.

## Validation / 验证边界

Local checks passed: **48 Web/host-contract tests + 16 upstream-contract tests**, TypeScript, **72 Core JVM tests (71 passed, one opt-in real-model test skipped)**, **13 App JVM tests**, and **9 Engine JVM tests** (fake runtime). Five focused Web tests cover draft-to-error rendering, safe literal text, cancellation, retry success and accepted-neighbour preservation. Pause-policy tests retain active/error/review states and the resume cursor.

Full native-enabled ARM64 debug APK and instrumentation APK compilation, Lint (**0 errors / 53 warnings**), debug signature, ZIP/ELF **16KiB alignment**, notices and no-model distribution checks passed. A local debug APK has a different certificate and is not a published-release upgrade package.

Actual desktop Edge DOM checks passed for visible inline errors, long-code wrapping, literal diagnostic text, successful retry, preserved neighbouring translation, and light/dark styling. Translation events in that check are simulated; it is not Android execution or model inference. This version has no new local emulator, physical-device, instrumentation execution, model inference/download or full-paper validation. Earlier alpha.16/alpha.17 model/emulator results remain historical evidence, not a new alpha.18 measurement. CI and signed release workflows separately recheck the source and run their configured browser/build gates. See [validation details](https://github.com/kejie-tang/pararead-android/blob/main/docs/reports/alpha18.md).

This is an error-display improvement, not a guarantee that qwen3:0.6b (or any model) will always produce valid/accurate translations. Failure details are session UI, not successful cached translations; reopening can restore valid results or request missing ones again.

## Install / 安装

Android 9+ / ARM64, **versionCode 18**. The signed release must retain certificate SHA-256:

```text
e044c8f329f42465866add4d041f29c498009361241610c8ee2de43ae6e87a45
```

Install over the previous same-signed preview to retain private PDFs, translations, models and reading state. **Do not uninstall to bypass an upgrade/signature error.** APK, notices and SHA-256 checksums are attached. Model weights and third-party papers are not bundled.
