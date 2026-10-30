# ParaRead Android 0.2.0-alpha.14 — a library that remembers / 从上次的位置继续读

## English

- **A compact PDF library.** First-page thumbnails and longer titles replace the large grid tiles. The introduction appears only when the library is empty.
- **Continue where you left off.** The top card resumes your most recently opened document and shows its saved page, progress and saved-translation indicator. Opening a PDF now updates the recent-reading order.
- **Rename or remove documents.** Each row has a document menu. Reimporting an identical PDF preserves its custom name and reading state. Deleting removes the app-owned PDF, reading records and annotation draft; the external original and shared sentence translations remain.
- **Reliable page restoration.** PDF.js now receives the saved bookmark during initialization, preventing a late initial-view update from resetting the restored page to page 1.
- Native library labels and dialogs support English and Simplified Chinese. Existing reading data is retained without a database migration. Model downloads and inference settings are unchanged.

## 中文

- **PDF 书库改为紧凑列表。** 展示首页缩略图，长标题有更多空间，介绍卡片只在书库为空时显示。
- **顶部继续阅读。** 恢复最近打开的 PDF，显示保存的页码、阅读进度和译文保存状态；按实际打开时间更新顺序。
- **支持重命名和删除。** 文档行的 `⋮` 菜单提供操作；重复导入同一 PDF 保留自定义名称与进度。删除会移除 App 内副本、阅读记录与批注草稿，外部原文件和共享句级译文缓存会保留。
- **修复页码恢复时序。** 初始化时把保存位置交给 PDF.js，避免短暂恢复后又跳回第 1 页。
- 书库和对话框支持中英文，沿用已有数据库与阅读数据；下载与推理设置保持原样。

## Validation / 验证

Debug and signed release builds and Android Lint passed locally. Nine Android instrumentation cases cover library persistence, page restoration, translation recovery, fullscreen controls and interface language. The library scenario also passed on a 360dp phone and a 1280dp tablet layout. TypeScript, Android Web tests and the PDF.js Chromium touch harness passed, including cached translation restoration and incremental annotation export.

The tests use generated PDFs, the original project guide and Mock/seeded translations. [Validation details](https://github.com/kejie-tang/pararead-android/blob/main/docs/reports/alpha14.md).

## Install / 安装

Android 9+ / ARM64, **versionCode 14**. The retained preview signing identity supports in-place upgrades. Certificate SHA-256: `e044c8f329f42465866add4d041f29c498009361241610c8ee2de43ae6e87a45` (Android Debug identity, retained for preview releases).

覆盖安装可保留 App 数据。模型权重和第三方论文不随 APK 分发。
