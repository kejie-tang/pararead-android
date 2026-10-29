# PDF 译文恢复与全屏阅读

基于 `kejie-tang/pararead-android` 的 main 提交 `86d366ae456002d4f9ffe86f77808461d6bda630`。

## 修复

原来 Room 已保存通过校验的译文，但只有进入翻译队列时才查缓存。手动范围下重新进入 PDF 不会主动查译文；内存淘汰后返回旧页也有相同问题。

现在页面就绪后先走 `translation.cacheGet`：以原文和当前配置的 contentKey 查 Room，逐句返回已保存结果，不进入 TranslationQueue、不调用模型/API。恢复期间同页请求去重；reset/淘汰后丢弃迟到结果。自动模式仅翻译剩余句子，手动模式不自动翻译缺失句子。数据库结构及既有缓存均保留。

阅读工具栏增加“全屏”选项，隐藏 App 侧栏、顶部应用栏、状态信息和系统栏，保留 PDF.js 的基础操作和译文栏。右下角按钮或 Android 返回键退出；全屏偏好持久保存。切换仅改变布局，不改变 readerVersion，不重建 WebView、不取消翻译。

## 本地验证

- `npm run check:reader`：通过。
- `npm run test:android-web`：21/21 通过（含新增 8 项恢复/桥接测试）。
- `npm test`：16/16 上游契约测试通过。
- `npm run build:reader`：通过。
- `PLAYWRIGHT_CHANNEL=msedge npm run test:reader`：通过。实际加载 PDF.js、渲染双栏、双向选句、横竖屏工具栏、增量批注保存后，重新加载为手动模式，验证译文恢复可见且没有 `translation.request`。详见 `reader-recovery-browser.json`。浏览器日志中有一个被既有 harness 容许的 404（页面图标资源）；其余验证通过。
- 环境：Windows、Node.js 22.23.3（项目正式构建要求 24+）、Microsoft Edge / Chromium、Playwright 触摸模拟。不是 Android 真机或模拟器测试。

新增 `ReaderRecoveryUiTest` 覆盖 Room 译文恢复、恢复时无翻译队列指标、全屏不重建 WebView、退出入口与 Activity 重建后偏好恢复。**当前环境无 JDK / Android SDK，未运行 Android 编译、instrumentation 或真机验证，未生成 APK。**

## 使用与限制

- 新增“全屏”为可选项，初始关闭；第一次打开后记住选择。
- 更改模型、目标语言、术语表等配置后不会套用旧配置的译文。
- 已提交的译文可恢复；退出时尚未完成/校验/提交的生成内容不保证保存。
- 译文仍在 App 专属数据库中，不自动嵌入导出的批注 PDF。卸载或清除 App 数据仍会删除它们。
- 本地修改未推送到 GitHub。
