# ParaRead Android

**原文与译文并排，让外语 PDF 更容易读下去。**

保留论文原有版面，点一句就能找到对应译文；在 Android 平板上，用本地 AI 辅助阅读论文和技术文档。

[![Android CI](https://github.com/kejie-tang/pararead-android/actions/workflows/android.yml/badge.svg?branch=main)](https://github.com/kejie-tang/pararead-android/actions/workflows/android.yml) [![Android 9+](https://img.shields.io/badge/Android-9%2B-3DDC84?logo=android&logoColor=white)](#快速开始) [![代码许可：Apache-2.0](https://img.shields.io/badge/Code-Apache--2.0-blue)](LICENSE)

**[下载预览版 APK](https://github.com/kejie-tang/pararead-android/releases/download/v0.2.0-alpha.2/ParaRead-0.2.0-alpha.2-android-arm64-debug.apk)** · [全部版本](https://github.com/kejie-tang/pararead-android/releases) · [快速开始](#快速开始) · [English](README.md)

基于 [ParaRead](https://github.com/Chuanyunux/pararead) 0.6.1 的独立、非官方 Android 移植与扩展。**当前为开发预览版，仅支持 ARM64；应用界面目前主要使用中文。**

![自编示例的双语阅读布局，原句与译文面板通过选句高亮和连线对应](docs/reports/reader-landscape.png)

*截图使用自编示例，在桌面 Chromium 中模拟触控，用于展示布局与联动。右侧是明确标记的 Mock 输出，不是真实模型译文，也不是实体平板截图。*

## 读译文，也随时看得见原文

读到陌生的术语或不确定的表达时，原句就在旁边。图表、公式与论文版面继续保留，方便把译文放回上下文核对。

| 功能 | 阅读时能做什么 |
| --- | --- |
| 句子级对齐 | 点击原句或译文，双向定位对应内容；支持高亮、同高对齐与同步滚动。 |
| 本地离线翻译 | llama.cpp 在设备上运行 Qwen3 GGUF，下载模型后即可使用本地翻译。 |
| 按自己的节奏阅读 | 翻译选中段落、当前页、邻近页或全篇，需要时随时取消。 |
| 保存阅读成果 | 复用译文缓存与阅读位置；全篇逐页保存进度，同一文档和配置重开后可继续。 |
| 批注与导出 | 保留本地批注草稿，另存带批注的 PDF 副本。 |
| 自己选择翻译配置 | 下载、校验、导入或删除支持的模型，调整 CPU 线程数和术语表；也可选择兼容 API。 |
| 测试本机速度 | 直接在 App 内运行真实模型测速，对比响应耗时、token/s，并导出结果。 |

## 快速开始

**设备要求：** Android 9 / API 28 及以上、ARM64，以及支持 `WebMessageListener` 的 Android System WebView。布局面向平板设计。

1. **安装[预览版 APK](https://github.com/kejie-tang/pararead-android/releases/download/v0.2.0-alpha.2/ParaRead-0.2.0-alpha.2-android-arm64-debug.apk)。** 当前下载为 debug 签名的 `0.2.0-alpha.2`。若旧版签名不同，可能需要卸载后安装；卸载会清除应用数据，请先另存重要的批注 PDF。
2. **打开文档。** 点击“打开示例文档”，或通过系统文件选择器导入有可选中文字的 PDF。
3. **下载本地模型。** 点击书库的“下载默认模型”，或进入“设置 → 离线 GGUF”。应用会在完整大小与 SHA-256 校验通过后启用模型。
4. **开始阅读。** 点击句子，翻译本页，或启动全篇任务；在“目标语言”中调整译文语言。主动取消后，点击“全篇 / 继续”接续任务。

### 本地模型

| 项目 | 当前默认配置 |
| --- | --- |
| 模型 | Qwen3 1.7B，Q4_K_M，关闭思考 |
| 下载量 | 约 **1.36 GB**，与 APK 分开下载 |
| 可用空间 | 下载与校验阶段约需 **2.8 GB**，另需预留 PDF 空间 |
| 推理方式 | llama.cpp CPU 推理，可自动或手动设置线程数 |
| 来源 | Ollama registry 的固定 GGUF 层，以 SHA-256 校验；**无需安装 Ollama** |
| 手动导入 | 本预览版仅支持 Qwen3 架构的 GGUF |

下载通过 Android 系统队列执行，支持进度、取消、重试和重开后继续监控。删除模型会保留文档、阅读进度与译文缓存。实现和测试细节见[模型管理](docs/reports/model-management.md)与 [CPU 线程设置](docs/reports/cpu-threads.md)。

### 原文会发到哪里

- **本地模式：** 在设备内翻译。首次下载模型需要联网；本地推理不把原文发给远端翻译服务，也不会自动切换远端 API。
- **API 模式：** 选中的待翻译文本与提示会发送到你配置的服务。外网端点要求 HTTPS，本地 HTTP 服务也可使用。
- **Mock 模式：** 仅供体验交互和开发测试，输出有明确标记，不能用于判断翻译质量。

导入的 PDF 会复制到应用私有目录，阅读状态、草稿和译文缓存也保存在本机，因此导入需要额外磁盘空间。API 与本地服务配置见[后端指南](docs/local-llama.md)。

## 用一篇熟悉的论文试试看

应用内置自编的六页 [ParaRead Reading Guide](samples/pararead-reading-guide.pdf)，包含双栏文字、公式、表格与矢量流程图。文字和图形采用 Apache-2.0，见[示例声明](samples/NOTICE)。

也可以从 [arXiv 官方页面](https://arxiv.org/abs/1706.03762)下载 Vaswani 等人的 *Attention Is All You Need*（2017），再导入自己的副本。仓库、APK 和发布附件均不附带该论文；其[分发许可](https://arxiv.org/licenses/nonexclusive-distrib/1.0/license.html)与版权独立于本项目的代码许可证。Android 构建排除了上游 PDF.js 示例 PDF。

## 速度测试

**直接在 App 测自己的设备：**打开 **设置 → 离线 GGUF → 速度测试**，点击“开始测速”。需先下载或导入模型。测试使用设置中当前填写的 CPU 线程数，无需先保存。

- 对固定的英文句子连续英译中四次：首次新加载模型测一次，模型已加载后再测三次，不使用译文缓存。
- 显示首次流式响应、完成耗时，以及三次热模型测试的中位数。生成速度按底层实际 token 数除以生成阶段耗时计算，包含 JSON 格式 token。
- 支持取消和旋转屏幕。最近一次完整结果保存在本机，可导出 JSON，包含模型哈希、设备、线程数、引擎版本及四次原始数据。

测试期间阅读器暂停，关闭后恢复。首次耗时包含模型加载，未清空系统文件缓存；首次响应可能是 JSON 语法。它衡量固定短句的翻译速度，不代表整篇 PDF 吞吐或翻译质量。详见 [App 内测速说明与验证](docs/reports/app-speed-test.md)。

### 早期参考数据

测试日期：**2026-10-26**。使用真实 **Qwen3 1.7B Q4_K_M**，英译中、关闭思考、CPU 推理、上下文 4,096 tokens。以下为引擎/集成链路耗时；实体平板速度和 WebView 端到端延迟仍待测试。

| 环境 | 工作负载 | 首个流式响应 | 完成耗时 | 样本数 |
| --- | --- | --- | --- | --- |
| M1 Pro 主机，6 线程 | 首次单句，模型尚未加载 | **1.70 秒** | **2.92 秒** | 3 次中位数 |
| M1 Pro 主机，6 线程 | 四句批次，模型已加载 | **0.68 秒** | **5.42 秒** | 3 次中位数 |
| Android ARM64 模拟器，自动 6 线程 | 首次两句，模型尚未加载 | 1.92 秒 | 9.79 秒 | 既有单次记录 |
| Android ARM64 模拟器，自动 6 线程 | 取消后再次翻译两句，模型已加载 | 1.60 秒 | 9.31 秒 | 既有单次记录 |

主机为 Apple M1 Pro、32 GiB RAM；模拟器为 8 vCPU、6 GiB RAM。Android 每个批次都包含 **3 次模型调用**：首轮批次校验未通过，再以两个单句修复，重试耗时全部计入。两端输入不同，不用于平台速度排名。首个流式响应可能只是 JSON 语法，不等于首个译词，也不是 tokens/s。首次耗时包含模型加载，未清空系统文件缓存。

<details>
<summary>自己跑一次真实模型测速</summary>

配置好[源码构建环境](#从源码构建)，将环境变量指向本机的默认 GGUF。脚本会核验 SHA-256，启动三个独立 JVM/provider 会话，保留所有原始值、中位数和范围，不下载模型权重。

```sh
export PARAREAD_GGUF="/absolute/path/Qwen3-1.7B-Q4_K_M.gguf"
python3 scripts/benchmark_translation.py --runs 3 --threads 6
```

输出为 `artifacts/translation-speed/summary.json`，逐次运行日志保存在同级子目录；`--threads 0` 使用自动线程策略。详见[测试方法与数据](docs/benchmarks.md)、[主机原始记录](docs/reports/translation-speed-host.json)与 [Android 参考记录](docs/reports/cpu-threads-android-real.json)。

</details>

## 当前进展

**这是可以体验的开发预览版，实体平板验证仍待完成。** 现有证据覆盖 28 项 Kotlin 核心测试、13 项 JS/bridge、12 项 Android ARM64 模拟器检查（含真实模型 App 内测速），以及 Chromium 阅读器与批注保存重开验证。真实 Qwen3 已通过宿主机和 Android ARM64 模拟器推理冒烟测试。范围见[验证记录](docs/reports/verification.json)与[公开构建检查](docs/reports/public-upload.md)。

实体平板性能、真模型持续阅读、学术翻译质量、强杀/重启恢复、无障碍和触控笔仍待验收。100/300/500 MiB 合成 PDF 测试只提供有限基线，不代表真实文档与模型并行运行的完整性能结论。本预览版不提供纯图片扫描件的 OCR 流程，未实现旧 VS Code 缓存导入；超过 512 页的表单字段修改目前拒绝保存，损坏或异常复杂 PDF 也可能触发明确的资源限制。细节见[兼容性](docs/compatibility.md)、[性能](docs/performance.md)和[任务清单](docs/tasks.md)。

**欢迎反馈真实平板上的使用体验。** [提交 Issue](https://github.com/kejie-tang/pararead-android/issues) 时，请附设备、Android/WebView 版本、后端/模型与复现步骤。需要提供 PDF 时，请使用你有权分发的小样本，不要上传 API Key 或私人文档。

## 从源码构建

环境：**Node.js 24+**、**Python 3.9+**、**JDK 21**、**Android SDK 36**、**NDK 28.2.13676358**、**CMake 3.22.1**。设置 `JAVA_HOME` 与 `ANDROID_HOME`，或在 Android Studio 中配置 JDK 21。

```sh
npm ci
python3 scripts/fetch_upstream.py
python3 scripts/fetch_llama.py
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

两个获取脚本均按固定 Git blob 哈希核验上游文件。源码快照位于忽略的 `.references/`，构建不会下载模型权重。`-PskipNative` 仅用于不含 JNI 的 Mock 原型，不能用于离线推理验收。

<details>
<summary>运行检查与查看技术文档</summary>

```sh
npm test
npm run check:reader
npm run test:android-web
npx playwright install chromium
npm run test:reader
./gradlew :core:test :app:lintDebug
```

Android instrumentation 需要连接 ARM64 设备或模拟器；真模型测试需要文档所述模型样本，请先查看报告再运行完整套件。大文件基准脚本也会生成大量测试数据，并操作连接的 debug 设备。

- [架构](docs/architecture.md)与[消息协议](docs/bridge-protocol.md)。
- [兼容性](docs/compatibility.md)、[测试记录](docs/reports/verification.json)与[性能](docs/performance.md)。
- [全篇恢复](docs/reports/whole-document-recovery.md)与[待办](docs/tasks.md)。

</details>

需要更新的 debug 构建时，进入 [Android CI](https://github.com/kejie-tang/pararead-android/actions/workflows/android.yml) → 成功的运行 → **Artifacts** → `pararead-android-debug-arm64`。下载需登录 GitHub，产物保留 14 天；不同运行的 debug 签名可能不同。带版本号的 APK、校验摘要和通知包放在 [Releases](https://github.com/kejie-tang/pararead-android/releases)，签名与发布步骤见[发布指南](docs/publishing.md)。

## 来源、致谢与许可

基于 Chuanyunux 的 [ParaRead](https://github.com/Chuanyunux/pararead)，并保留其上游 **Mathematic Inc / vscode-pdf** 与 **Mozilla PDF.js** 归属。TypeScript/PDF.js 阅读器来自 ParaRead；本项目新增或适配 Android 宿主、文件访问、持久存储、翻译调度、模型管理和 JNI。离线推理由 [llama.cpp](https://github.com/ggml-org/llama.cpp) 与单独下载的 Qwen3 权重提供。

Android 原创代码与适配修改采用 **[Apache License 2.0](LICENSE)**。第三方代码、字体、WASM 和模型内容保留各自许可；分发时保留 [NOTICE](NOTICE)、[第三方声明](THIRD_PARTY_NOTICES.md)和[许可原文](third_party/)。项目许可证不授予用户导入的 PDF、任意模型及译文的使用权。

这是独立的非官方项目，与上游项目或模型提供方无官方隶属或背书关系，名称与商标归相应权利人。PocketPal、Readest、MJ PDF、PocketLLM 仅作行为或架构参考，未复制其代码或资源，见[来源与许可审查](docs/licenses.md)。
