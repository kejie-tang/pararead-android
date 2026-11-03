# ParaRead Android 0.2.0-alpha.19 — TXT、只阅读与焕新控件 / TXT, Read only and refreshed controls

## 中文

- **新增 TXT 阅读器。** 支持导入 TXT，自动识别或手动选择 UTF-8、UTF-16LE/BE、GB18030、GBK；提供固定内容分页、页码跳转、章节目录、12–32 字号及阅读位置恢复。自动编码识别并非绝对可靠，乱码时请重新导入并指定编码。
- **平板左右对照。** 宽屏 TXT 左侧原文、右侧译文，逐段对齐；手机上下排列，隐藏原文后恢复单栏。字号和方向变化不改变翻译页标识或断点。分页按内容，不是屏幕高度，页内仍可滚动。
- **PDF/TXT 共用“只阅读”。** 可记忆的独立选项，仅显示原文，不请求译文缓存或调用模型/API；保留翻页、字号/缩放、全屏和阅读进度。PDF 继续支持批注及导出。退出只阅读后仍可使用原有译文缓存；书库中明确启动的后台翻译独立保留。
- **安全切换模式。** 先确认阅读位置及必要的 PDF 批注保存，再重开阅读器；保存失败保留原模式与当前编辑。全篇断点、正常译文和既有文档继续保留。
- **焕新阅读控件。** 圆角按钮、主次操作、分组工具栏、页码/目录输入、字号滑块及原文开关，统一浅深色主题和键盘焦点。翻译错误使用低调灰色，无红色波浪线，仍显示诊断并可重试。
- TXT 复用现有翻译、缓存和书库全篇翻译；不转换为 PDF，不提供 PDF 专属批注或导出。未改数据库结构、模型引擎、提示词及全局缓存指纹，不清除用户数据。

## English

- **TXT reader:** automatic or explicit UTF-8, UTF-16LE/BE, GB18030 and GBK import; stable content pages, page/chapter navigation, 12–32 font sizes and restored reading position. Encoding detection is fallible; reimport with an explicit encoding if needed.
- **Aligned tablet layout:** source left, translation right, paragraph by paragraph; stacked on phones and single-column with source hidden. Font/orientation changes preserve translation identities and checkpoints. Content pages scroll internally rather than repaginating to screen height.
- **Shared PDF/TXT Read only:** a remembered option showing originals without translation-cache requests, model loading or inference. Navigation, fullscreen and reading state remain; PDF annotations/export remain available. Existing translation caches and explicitly requested library background jobs are retained.
- **Save before switching:** acknowledged reading-state/annotation persistence precedes reopening the reader; failures preserve the current mode and edits.
- **Refreshed controls:** rounded, grouped actions; clearer primary buttons, page/chapter fields, font slider and source switch; coherent light/dark styles and keyboard focus. Translation errors are subdued gray without red wavy underlines, retaining diagnostics and retry.
- Retains background translation and valid caches. No engine, prompt, global cache-fingerprint or database-schema change; no data wipe. TXT does not become a PDF and has no PDF annotation/export controls.

## Validation / 验证边界

Local checks: **120 Web/upstream tests**, TypeScript, **94 Core JVM tests (93 passed, one optional real-model test skipped)**, **18 App JVM tests** and **9 Engine JVM tests** using a fake runtime. Full native-enabled ARM64 debug APK and instrumentation APK builds, Lint (**0 errors / 54 warnings**, full build check), debug signature, ZIP/ELF **16KiB alignment**, notices and no-model distribution checks passed.

Actual desktop Edge checks cover normal PDF reading, annotations/fullscreen, headless PDF translation, TXT navigation/chapters/fonts/cache/background barriers, and PDF/TXT Read only save success/failure. UI checks cover 320/360/800/1280 widths, light/dark, 44px Web buttons, body/source/enabled-button contrast ≥4.5, enlarged UI text and keyboard focus. Native bridge, persistence and translation events in browsers are simulated. These checks are **not Android-device, instrumentation-execution or real-model evidence**. No new device/emulator run or model download/inference was performed; earlier releases' model/device results remain historical. CI and signing workflows separately rerun their configured gates, including both new browser suites. See [alpha.19 validation](https://github.com/kejie-tang/pararead-android/blob/main/docs/reports/alpha19.md).

## Install / 安装

Android 9+ / ARM64, **versionCode 19**. The signed release must retain certificate SHA-256:

```text
e044c8f329f42465866add4d041f29c498009361241610c8ee2de43ae6e87a45
```

Same-signed previews can be upgraded in place, retaining private documents, translations, models and reading state. **不要为绕过签名错误卸载旧版 / Do not uninstall to bypass a signature error.** The persistent preview certificate identifies as Android Debug; this is not a production-signing claim. APK, notices and SHA-256 checksums are attached. No model weights or third-party papers are bundled.
