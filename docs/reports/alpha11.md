# Alpha.11：下载、界面语言与项目展示

日期：2026-10-28。版本 `0.2.0-alpha.11`，versionCode 11。基于 alpha.10 的 CPU 插件与持久线程池；本次未改变原生推理参数或宣称新的推理提速。

## 下载只保留一份权重

新下载从 Android DownloadManager 改为 WorkManager 前台 worker，通过 OkHttp 写入内部 `files/models/model-<uuid>.part`。固定目标、任务 ID 和临时文件名持久化。网络故障保留部分文件，按实际长度发起 HTTP Range 请求；服务器忽略 Range 时截断同一文件重下。非预期范围、超长文件及错误摘要均不发布。

下载完成后检查 GGUF 文件头、精确字节数和 SHA-256，然后在同目录原子重命名为 `<sha256>.gguf`。无需第二份完整权重。新下载检查剩余字节加 64 MiB 余量：Qwen3-0.6B 约 464 MB，1.7B 约 1.43 GB；其他已保存模型和文档另计。已有部分文件续传只检查剩余所需空间。

WorkManager 负责后台重试和进程恢复；App 观察到成功任务后登记并启用模型。自动网络重试耗尽后保留进度供手动重试。App 内取消会先取消网络并等待目录锁，再清理临时文件。通知栏取消也会取消任务，App 下次观察到该状态时清理临时文件。校验失败不覆盖旧模型。进程若在重命名后、记录任务成功前退出，下次先验证已发布文件，避免重复下载。

旧版本正在进行的 DownloadManager 任务继续走兼容路径，仍可能短暂使用双份空间；已保存模型无须迁移或重下。手动导入外部 GGUF 仍复制到私有目录，不删除用户原文件。

### 验证证据

- 核心层真实 HTTP fixture 覆盖续传、忽略 Range、错误范围、截断重试、错误 SHA、阻塞读取取消、超长响应、完整临时文件免请求，共 8 项；包含发布前后同一 inode 检查。
- Android 下载测试观察下载期间 `.part` 与模型文件总字节不超过一份权重，并比较最终 inode；同时检查无外部第二份下载。使用约 8 MiB 的合成 GGUF 头 fixture，验证传输与发布，不用于推理质量判断。
- 已发布文件恢复测试：预先存在已校验大小的模型、尚未登记库，worker 校验并登记，HTTP 请求数为零。
- **实际进程停止恢复**：第一次 invocation 下载 262,144 字节后，用 `adb shell am force-stop org.pararead.android` 停止进程，再启动第二次 invocation。服务端记录 `Range: bytes=262144-`；最后精确 8,388,612 字节、SHA 匹配且目录只有一个发布文件。[结果 JSON](alpha11-download-recovery.json)、[HTTP 请求](alpha11-download-recovery-http.jsonl)、[准备日志](alpha11-download-recovery-prepare.txt)、[恢复日志](alpha11-download-recovery-resume.txt)。这是同一测试的两个阶段，不能算两个独立恢复用例。

## 中英文菜单

Settings / 设置增加“界面语言”：System / 跟随系统、简体中文、English，选择立即保存和应用。系统选项使用设备语言，中文环境选中文，其余环境选英文。界面资源覆盖书库、设置、模型管理、Hugging Face 目录、测速和原生阅读工具栏；文档与模型名称保留自身文本，嵌入 PDF 查看器及原始服务端/驱动诊断可能保留自己的语言。

实现使用 Compose 的资源上下文，Dialog 内也显式继承所选语言；不修改全局 Locale、不重建 Activity 或 WebView、不修改翻译配置/缓存指纹。测试覆盖即时切换、Activity 重建后保留、嵌套下拉菜单及模型卡按钮、翻译参数不变，以及打开阅读器后切换仍为同一 WebView 实例。

真实 Android 模拟器截图：[英文书库](../screenshots/library-en.png)、[英文设置](../screenshots/settings-en.png)、[中文书库](../screenshots/library-zh.png)、[中文设置](../screenshots/settings-zh.png)。

## README 与图标

英文为默认首页，中文单独维护；先展示双语阅读用途、安装入口与截图，再介绍模型、空间、测速、隐私和许可。天玑 9400 的 26 / 23 token/s 明确标为用户反馈，缺少原始测速导出，不作为独立测量或跨设备保证。

图标为本项目原创双页书本，使用蓝色背景、浅色对照页面和对齐文字线，提供 Android adaptive 与 monochrome 资源。原始 SVG、PNG 与声明见 [branding](../branding/README.md)，采用项目 Apache-2.0，无外部图案或字体。第三方通知补充 WorkManager 与原创图标归属，依赖树更新至实际 debugRuntimeClasspath。

## 本次验证

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| 核心 Kotlin | 52 通过，0 失败/跳过 | [验证汇总](alpha11-verification.json) |
| 引擎 JVM | 8 通过，0 失败/跳过 | 同上；未改原生实现 |
| 上游源码契约 | 16 通过 | [日志](alpha11-source-tests.txt) |
| Android API 36 / ARM64 综合回归 | 24 通过，1 可选 Hugging Face 在线下载测试未启用而跳过；84.224 秒 | [日志](alpha11-android-tests.txt) |
| 最终 Dialog 语言修复后的加强回归 | 2 通过；7.258 秒 | [日志](alpha11-locale-tests.txt) |
| 实际进程停止续传 | 两阶段均通过；0.503 / 13.293 秒 | 上述下载恢复证据 |
| APK / 测试 APK / Lint | 构建成功；Lint 0 错误、44 警告 | [最后生产构建日志](alpha11-build.txt) |
| 分发检查 | ARM64、四种 CPU 插件、Vulkan、ELF/ZIP 16 KiB 对齐、无 GGUF、仅原创示例 PDF、通知原文匹配、签名有效 | [验证汇总](alpha11-verification.json) |

综合回归包含模型取消/重试/切换/删除与旧模型保留、Hugging Face UI、线程与后端设置、真实 Qwen3-1.7B 的翻译与测速、旋转/取消/持久化、Reader/JNI/缓存/批注集成。综合回归后仅修复了 Dialog 的语言上下文，再单独运行两项加强语言回归；进程恢复测试也在此最终 APK 上执行。

本机发布 APK 继续使用 alpha.8–alpha.10 同一调试证书，证书 SHA-256：`e044c8f329f42465866add4d041f29c498009361241610c8ee2de43ae6e87a45`。同签名覆盖升级保留私有数据；卸载或清除数据仍会删除它们。APK/通知 ZIP 的精确字节数与摘要见验证汇总及 Release 附件 `SHA256SUMS.txt`。

本次设备验证为 API 36 ARM64 模拟器，未新增实体手机/平板验收，未对生产 CDN 完整下载 1.36 GB 权重。进程停止恢复不等于验证系统重启、长时间 Doze、厂商省电策略或磁盘断电恢复。真实模型推理与合成下载 fixture 是独立测试，不能用下载测试推导翻译质量或真机速度。
