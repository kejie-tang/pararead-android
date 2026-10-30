# ParaRead Android 0.2.0-alpha.13 — more room to read / 全屏自动收起工具栏

## English

- **Read without the toolbar taking up space.** In full screen, swipe up to continue reading and both the PDF toolbar and translation header collapse. Swipe down or tap the small arrow to restore them.
- Works from either pane. Translation alignment and other programmatic scrolling do not toggle the controls. Annotation tools, pinned sentences, native text selection, input controls, and pinch zoom keep them visible.
- The reveal arrow stays inside the PDF pane on narrow screens. The bottom-right exit button and Android Back remain available. Exiting full screen restores both headers; entering a new reader starts with visible controls.
- Normal reading mode keeps its toolbar. No changes to model downloads, inference configuration or translation cache; full-screen changes keep the same WebView and translation session.

## 中文

- **全屏上滑自动收起菜单。** 向上滑继续阅读时，PDF 工具栏和译文顶部栏一起收起；向下滑或点击小箭头恢复。
- 原文和译文两侧均可操作，自动对齐滚动不会误触发。批注、选中句子、复制文字、输入控件及双指缩放时保持显示。
- 小箭头跟随实际 PDF 区域，适配窄屏。右下角退出按钮和 Android 返回键保留；退出全屏恢复菜单，重新打开阅读器时先显示菜单。
- 普通模式不变，不改动下载、推理配置或译文缓存，切换全屏不重建 WebView 和翻译会话。

## Validation / 验证

TypeScript, 21 Android Web tests, 16 source-contract tests, 52 core tests, 8 engine JVM tests, debug/test APK compilation and release build/Lint passed. The real PDF.js Chromium touch harness checks both swipe directions and panes, reveal-button access on a narrow screen, selection/annotation protection, ignoring programmatic scrolling, full-screen exit, cached translations, selection alignment and annotation saving.

API 36 ARM64 emulator: 10 instrumentation cases passed in tablet layout; the same 4 reader cases also passed in phone layout. A separate normal-entry regression also passed on the actual local signed release APK in both layouts, including entering full screen before the PDF finishes loading. Tests use the original sample guide and Mock/seeded translations. This does not establish physical-device inference performance or model quality. [Validation details](https://github.com/kejie-tang/pararead-android/blob/main/docs/reports/alpha13.md).

## Install / 安装

Android 9+ / ARM64, **versionCode 13**. The APK uses the retained preview signing identity for in-place upgrades. Certificate SHA-256: `e044c8f329f42465866add4d041f29c498009361241610c8ee2de43ae6e87a45` (Android Debug identity, retained for preview releases).

覆盖安装会保留文档、模型和已保存译文。若安装失败，不要为了绕过错误直接卸载：卸载或清除数据会删除 App 私有内容。模型权重和第三方论文不随 APK 分发。
