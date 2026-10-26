ParaRead Android 开发预览版：非官方 Android 平板移植与扩展。

- 句子级双语 PDF 阅读、双向定位、同步滚动。
- ARM64 llama.cpp 本地翻译；默认 Qwen3 GGUF 单独下载，不包含在 APK 中。
- 模型下载/校验/删除、CPU 线程设置、持久翻译缓存和全篇任务恢复。
- 内置自编六页阅读指南；不包含第三方论文。

要求：Android 9+、ARM64、支持 WebMessageListener 的 Android System WebView。首次模型下载需要联网，校验阶段约需 2.8 GB 空间。

本版本仍是开发预览。现有证据覆盖核心逻辑、浏览器及 ARM64 模拟器；实体平板、天玑 9400 性能、真实论文翻译质量、强杀/重启恢复及完整大文件矩阵尚未验收。不要将本次构建描述为稳定版。

安装 `*-release.apk`；`SHA256SUMS.txt` 提供完整性摘要；`*-notices.zip` 提供项目及第三方通知和本次 release 运行依赖清单。APK 内也保留相关通知。

首次从 debug 版切换到发布签名时，可能需要卸载旧版本；卸载会删除应用私有文档、缓存与模型，请先另存需要保留的批注 PDF。后续更新使用同一发布密钥并递增 versionCode。

推荐阅读：[Attention Is All You Need](https://arxiv.org/abs/1706.03762)，由用户从官方页面自行下载并导入；本发布不附带论文。
