# 首次公开上传验证

日期：2026-10-26。目标仓库：<https://github.com/kejie-tang/pararead-android>。

## 改动与公开范围

- 自编六页英文阅读指南（Apache-2.0），含双栏、公式、表格及原创矢量流程图；ReportLab 4.4.9 可重建，六页均已用 Poppler 渲染并目视检查，文本抽取正常。
- 内置示例改名“示例文档”；构建清理旧生成目录并排除 PDF.js 上游的全部 PDF。APK 中唯一 PDF 与仓库自编示例逐字一致，不含 GGUF。
- Attention Is All You Need 仅提供 arXiv 官方链接，不附带全文。
- Chromium 横竖屏截图重新生成；移除含旧论文内容的两张历史 Android 原型截图。旧开发历史保留在本机 `local/pre-publication-history`，公开 main 从整理后的快照开始；不上传旧图片的 Git 对象历史。
- README、NOTICE、第三方声明、许可说明同步更新；上游署名、根 LICENSE 和第三方许可原文保留。
- 增加 Android CI、手动签名 Release 草稿工作流、可选环境变量签名配置，以及实际 APK 资产/原生/通知校验与打包脚本。

## 本机验证结果

| 范围 | 结果 |
| --- | --- |
| TypeScript、上游契约、JS/bridge | 类型通过、16 / 13 项通过 |
| Kotlin 核心 | 24 项通过 |
| Chromium 阅读器 | 原创六页样本通过触控、双向定位、竖屏工具栏边界和批注保存后重解析；对齐误差约 0.306px；[日志](public-upload-browser.txt) |
| Android API36 ARM64 模拟器 | 9 项通过，含样本读取/缓存/批注草稿、模型管理、线程设置、全篇取消/重建恢复；[日志](public-upload-android.txt) |
| debug 构建 / Lint | 成功，0 错误 / 34 警告；[日志](public-upload-build.txt) |
| release 构建 / Lint | 成功，0 错误 / 34 警告；一次性测试证书验证，非正式密钥；[日志](public-upload-release-check.txt) |
| APK 实物校验 | debug/release 均通过 apksigner、ZIP/ELF 16KiB 对齐、ARM64 JNI、唯一自编示例、无 GGUF、项目及第三方通知字节核对 |
| 工作流 | actionlint 1.7.12 静态检查通过；Action 引用固定提交 |
| Markdown / Git | 已修改文档本地链接有效，git diff --check 通过 |

本机 apksigner 首次直接调用未选中 JDK，显式指定本机 JDK21 后重验通过；Gradle 构建本身正常。云端 setup-java 会设置 JAVA_HOME。

本次没有生产签名密钥、没有自动创建公开 Release，也没有进行实体平板和真模型长任务验收。正式发布建议使用 0.2.0-alpha.1 预览版、持久发布密钥、递增 versionCode，并按 [发布指南](../publishing.md)创建草稿后发布。当前 debug APK 摘要与大小见 [verification.json](verification.json)。云端构建状态以目标仓库 Actions 运行结果为准。
