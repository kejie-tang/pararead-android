# Phase 5 验证报告

日期：2026-10-26。交付：PDF.js 批注交互、增量保存/草稿/恢复、平板尺寸适配、异常处理路径及自动测试。**完整发布验收仍未通过**；硬件、真实模型及复杂 PDF 矩阵保持待办。

## 已实现与已执行

- 原版 PDF.js 高亮/文字/墨迹编辑界面保留；阅读 overlay 与永久批注分离。编辑模式暂停阅读选句。
- SaveDocument 不再请求整文件；worker 只产生增量 suffix，严格分块传入原生，fsync 后保存 Room 草稿。另存通过 SAF 流式合并为真实 PDF 副本。原文件不可变，失败不提前清 dirty；哈希观察与排队保存覆盖保存过程中的后续编辑。
- Chromium 对真实示例 PDF 加文字批注、产生约 1KB suffix，再以实际 PDF.js 重新解析，页数及批注内容正确；见 android-reader-browser.json。
- ARM64 instrumentation 完成真实 PDF.js → 原生持久草稿 → Activity 重建 → PDF.js getAnnotations 读回文字批注；Room 中存在译文。第二项测试验证3GiB稀疏源的Long偏移、并发读及baseline/suffix交叉边界。两项通过，见 android-instrumentation.xml。
- 100/300/500MiB 合成文字 PDF 的原生草稿写入结果见 android-large-pdf.json；额外以 pypdf/Poppler 检查 native suffix 合并后的页面和批注，结果见 native-incremental-validation.json。pypdf非零起始xref提示保留，Poppler无警告。pypdf非零起始xref提示保留，Poppler无警告。该项为合成文件增量校验，不包含系统 SAF 文件选择界面的全流程自动化。
- 横竖屏双栏截图、浏览器触摸点句与模拟器可见 canvas 已记录；非 touch hover 与笔编辑保护有代码，实体笔未测。
- RenderProcessGone 销毁旧会话后重开、模型未导入提示、输入/协议/HTTP 断流错误、取消和缓存路径有实现；异常矩阵仍不完整。

构建、Lint、测试输出在 android-build-tests.txt；Lint 0 error，warning 数与分类见 verification.json/android-lint.xml。锁定依赖没有为消除“有新版本”提示而盲目升级。本 APK 使用 Android Debug 证书，属于开发验证产物。

## 未实现或未验收

1. 实体 ARM64 平板飞行模式、真模型 WebView 整页联动、人工学术质量和长时资源/取消压力。真实 llama-server、macOS 共用 JNI 及 Android 模拟器原生测试已补充于 ../local-llama.md。
2. 大文件 SAF 另存全流程与输出复读、加密/签名/复杂 xref/object stream/旧批注保真、磁盘不足和授权撤销；保存峰值 PSS 未采样。
3. 永久高亮/ink/擦除的完整自动化与实体笔/鼠标/TalkBack/多窗口/手势矩阵。
4. 全篇强杀/真模型持续恢复验收、缓存命中及句子级分类计数、旧 VS Code 缓存导入、历史 Room 迁移、卡住的模型加载 watchdog。游标自动恢复与页计数已补充，见 [恢复报告](whole-document-recovery.md)。
5. 真实100/300/500MB论文、持续100页/全篇后内存收敛、Perfetto/Macrobenchmark与多次分位数。

这些项目继续保留于 tasks.md/compatibility.md；没有用静态双栏或整页纯文本代替句子定位、结构和双向同步。
