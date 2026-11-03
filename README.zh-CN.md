<p align="center"><img src="docs/branding/app-icon.png" width="88" alt="ParaRead Android 图标"></p>

<h1 align="center">ParaRead Android</h1>
<p align="center"><strong>一份 PDF，两种语言，一起读懂。</strong></p>

在 Android 上对照阅读论文与技术文档：原始页面放在身边，译文逐句对应。查看公式、核对引用、理解上下文，让本地 AI 帮你读下去。

[![Android CI](https://github.com/kejie-tang/pararead-android/actions/workflows/android.yml/badge.svg?branch=main)](https://github.com/kejie-tang/pararead-android/actions/workflows/android.yml) [![Android 9+](https://img.shields.io/badge/Android-9%2B-3DDC84?logo=android&logoColor=white)](#开始使用) [![代码：Apache-2.0](https://img.shields.io/badge/Code-Apache--2.0-blue)](LICENSE)

**[下载 APK](https://github.com/kejie-tang/pararead-android/releases/tag/v0.2.0-alpha.21)** · [全部版本](https://github.com/kejie-tang/pararead-android/releases) · [English](README.md)

面向 **Android 9+ / ARM64** 的开发预览版，为平板设计，也可在手机使用。原生菜单支持**简体中文、English 和跟随系统**。基于 [ParaRead](https://github.com/Chuanyunux/pararead) 0.6.1 的独立、非官方适配。

![PDF 原文与逐句对齐的译文](docs/reports/reader-landscape.png)

*阅读交互演示：桌面 Chromium 触摸模拟、自编示例文档和明确标注的 Mock 输出；不是实际模型译文或实体平板截图。*

### alpha.21 新增：保留不完美译文，普通错误只翻一次

有可用译文时，即使校验或生成报错也保留正文，并在末尾追加对应错误码，例如 `译文…… *****（protected_token_mismatch）`、`*****（truncated_stream_length）` 或其他实际错误。标注与附近正文同色同字体，浅/深色均无红色、下划线或额外强调。普通错误不再自动修复重译或隐式重发 HTTP；仅明确的后端兼容问题可回退 CPU。带标注的已保存译文重开直接复用；空输出、身份歧义与推理内容不会冒充译文。alpha.20 的阅读手势、安全保存和 PDF/TXT **只阅读** 均保留。[警告与单次生成规则](docs/translation-warnings.md) · [alpha.21 验证](docs/reports/alpha21.md)。

## 把时间花在阅读上

- **保留页面上下文。** 原文中的图表、公式与引用始终可见，译文在一旁对照。
- **逐句找到对应内容。** 点击任意一侧，联动高亮、位置对齐与滚动。
- **离线也能翻译。** 下载一次 GGUF，通过 llama.cpp 在设备上推理，无需安装 Ollama 或注册账号。
- **按自己的节奏读。** 翻译选段、本页或全篇，保存可安全归属的译文（含错误标注）和逐页进度，下次继续。
- **阅读前预翻译。** 书库文档竖向菜单选择“全部翻译”，不必打开阅读界面；逐文档显示排队、翻译页数及失败状态，可取消、继续或重试。
- **适合你的阅读习惯。** 保存批注、另存批注 PDF，设置术语表与翻译语言。
- **方便管理模型。** 下载、切换、删除，也可在 Hugging Face 搜索其他模型。
- **接着上次的位置读。** 紧凑书库展示首页缩略图、阅读进度和译文保存状态，顶部继续阅读最近打开的 PDF，文档菜单支持重命名与删除。

<details>
<summary>查看 Android 界面</summary>

<p><img src="docs/screenshots/library-zh.png" width="600" alt="Android 模拟器中的中文书库"></p>
<p><img src="docs/screenshots/settings-zh.png" width="600" alt="中文设置与界面语言选择"></p>

以上为 Android 模拟器实际截图。原创双页图标已用于启动器，并提供随系统配色的单色版本。

</details>

## 开始使用

1. **安装[预览版 APK](https://github.com/kejie-tang/pararead-android/releases/tag/v0.2.0-alpha.21)。** 需要 ARM64、Android 9+ 和较新的 Android System WebView。预览版沿用原签名以便覆盖升级；详见[验证与升级说明](docs/reports/alpha21.md)。不要为绕过签名错误直接卸载旧版，卸载会删除私有文档、译文缓存和模型。
2. **需要翻译时，在“模型管理”选择模型。** 0.6B 适合轻量快读，1.7B 偏重译文质量。下载校验通过后自动启用，其他模型会保留；**只阅读**无需模型。
3. **导入 PDF/TXT，或打开示例文档。** 可开启“只阅读”、翻译“本页”或使用“全篇 / 继续”。TXT 可指定编码；PDF 需要可选中文字，暂不提供 OCR。

在**设置 → 界面语言**切换原生菜单，即时生效，与“源语言”“目标语言”分别设置。内嵌 PDF 查看器及原始第三方诊断信息可能保留各自的语言。

### 恢复译文与全屏阅读

- 已保存的译文（包括带标注的不完美结果）保留在本地，未开启“只阅读”时，退出 PDF/TXT 后重新打开会恢复显示，**手动翻译模式也能恢复，不会为了恢复而调用模型或 API**。自动模式只补译缺失句子；手动模式需点击句子或“本页 / 全篇”才翻译缺失内容。
- **单击选中，当前页自动翻译。** 悬停不会选中句子，也不会控制是否自动翻译。首次使用默认自动翻译当前页；在**设置 → 自动翻译范围**可选择当前页、邻页或手动。跳页时当前页自动提优先级，可抢占低优先级的全篇及邻页预取推理，无须先点击句子；切换前仍等待旧推理安全释放资源；已保存的范围选择仍保留。
- 点击阅读工具栏的**全屏**，隐藏应用工具栏、状态信息和系统栏。PDF 上滑继续阅读时收起 PDF 工具栏和译文顶部栏，反向滑动或 Tab/Escape 恢复，不再显示小箭头；批注、句子或文字选择及双指缩放时保持菜单显示。TXT 可直接**显示/收起阅读工具栏**。使用 **Android 返回键**退出全屏，再次返回会先保存并回到书库，不再显示右下角退出按钮。宽屏书库侧栏在普通阅读和全屏时均隐藏。全屏偏好会保存，切换不会重建阅读器或中断翻译。
- 任何错误已有可用译文时都保留正文，末尾用普通文字追加 `*****（实际错误码）`。安全归属的带标注输出会保存并复用；保存失败仍保留可见正文，但不伪造保存成功。确实没有可用正文才显示错误及重试提示；失败可由用户主动重试，普通错误不自动再次生成，不影响取消与相邻正常译文。
- 缓存按原文、模型、语言及术语表等配置区分。更换配置后不会套用旧配置的译文；译文保存在 App 数据中，不会自动写入导出的批注 PDF。

### 先翻译，再阅读

在书库文档竖向菜单选择**全部翻译**。任务逐个执行，逐页保存进度，不改变阅读位置或最近阅读排序。打开文档、模型或设置界面会暂停后台任务，等待模型安全释放后再进入。可从同一菜单**继续全部翻译**；全部页处理结束后，如有失败，选择**重试失败译文**，保留正常缓存、补译缺失内容。有失败页时不会显示“全部翻译完成”。

全篇优先复用已保存译文（含带标注结果），仅补缺失内容或未标注的无效旧缓存；不会因同一警告反复推理，并按批次读取缓存。带标注的已保存结果计为已处理，不代表语义准确或完整。取消提示与书库中无错误的闲置“已暂停，可继续”进度行约 2.5 秒后消失，暂停行的进度条与间距一并收起。“继续全部翻译”仍在文档菜单中，已保存进度、活动任务、错误及待复核警告不会被清除。[alpha.18 验证](docs/reports/alpha18.md)。

Android 后台服务与系统配额可能中断长任务，不保证每种设备始终后台运行。中文目标会检查英文照抄，并选择性重新验证旧缓存；公式、URL、代码和短专名可原样保留。这不是语义准确性保证。[验证与边界](docs/reports/alpha16.md)。

### 选择适合的模型

| 模型 | 适合场景 | 下载大小 | 新下载所需可用空间¹ |
| --- | --- | ---: | ---: |
| **Qwen3-0.6B Q4_K_M** | 快速浏览、预览 | 397 MB | 约 464 MB |
| **Qwen3-1.7B Q4_K_M** | 质量优先，当前默认 | 1.36 GB | 约 1.43 GB |

¹ 模型大小加 64 MiB 预留，不含 PDF 和其他已保存模型。Alpha.11 直接下载到模型目录的临时文件，检查 GGUF、大小和 SHA-256 后原地重命名，**不再需要第二份完整权重**。支持后台任务、网络重试和 HTTP Range 续传；App 观察到任务完成后登记并启用模型。升级前已在进行的系统下载仍通过旧安装流程完成。[实现与验证](docs/reports/alpha11.md)。

0.6B 来自固定提交的 Unsloth Hugging Face 文件；1.7B 来自固定摘要的 Ollama registry 模型层。默认关闭思考。在**模型管理 → 更多模型（Hugging Face）**中可查找公开的单文件文本 GGUF；当前模板适配 Qwen2.5、Qwen3、Qwen3.5 和 LFM2/2.5。模型许可单独展示。[精确文件与兼容性实测](docs/reports/small-model-evaluation.md)。

小模型仍可能漏译或翻错数字关系。现在以标注保留不完美输出，不自动反复生成；已保存或无警告也不等于语义准确，重要结论仍应对照原文。

### 在自己的设备上测速

打开**设置 → 离线 GGUF → 速度测试**，运行四次长段翻译和三次纯引擎 **pp512 / tg128**。结果记录原生 token 数、首次与热模型耗时、CPU 内核、线程数和实际后端，可导出完整 JSON。

CPU 优化后，用户在**天玑 9400、Qwen3 1.7B Q4_K_M、CPU / 6 线程**上反馈：**翻译 26 token/s，tg128 为 23 token/s**。这是尚未附带原始导出的一台设备反馈，不代表所有设备表现。[CPU 排查与测试边界](docs/reports/cpu-threadpool.md)。

默认使用 CPU，自动选择支持的指令内核并复用工作线程池。Vulkan 为实验选项，仅明确的后端兼容错误回退 CPU；部分设备可能更慢或出现界面卡顿。[后端说明](docs/vulkan.md)。

## 文档由你掌握

- **本地模式：**设备上处理文档文字。下载模型需要网络，翻译不使用远程服务，也不会自动切换到云端。
- **API 模式：**选中的文字和提示会发送到你配置的端点；远程服务要求 HTTPS，本机服务支持 HTTP。
- **存储：**导入的 PDF/TXT、批注、阅读进度、译文与模型保存在 App 专属空间。相同签名覆盖升级会保留数据；**卸载或清除 App 数据会删除这些内容**，需要保留的批注 PDF 请先另存。单独删除模型会保留文档和译文。

内置六页[操作示例](samples/pararead-reading-guide.pdf)为本项目原创，采用 Apache-2.0。要尝试 *Attention Is All You Need*，请从 [arXiv 官方页面](https://arxiv.org/abs/1706.03762)下载后导入；本项目不打包或重新授权该论文。

## 构建与参与

需要 **Node.js 24+**、**Python 3.9+**、**JDK 21**、**Android SDK 36**、**NDK 28.2.13676358**、**CMake 3.22.1**，以及 `glslc`、Ninja；配置 `JAVA_HOME` 和 `ANDROID_HOME`。

```sh
npm ci
python3 scripts/fetch_upstream.py
python3 scripts/fetch_llama.py
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

macOS 请用 `scripts/gradle.sh` 替代 `./gradlew`，通过 `brew install shaderc` 安装着色器编译器；Ubuntu 使用 `apt-get install glslc ninja-build`。源码依赖固定版本并校验，构建不下载模型权重。[构建与后端](docs/local-llama.md) · [签名与发布](docs/publishing.md)。

<details>
<summary>检查命令与工程记录</summary>

```sh
npm test
npm run check:reader
npm run test:android-web
npx playwright install chromium
npm run test:reader
./gradlew :core:test :engine:llama:testDebugUnitTest :app:lintDebug
```

Android instrumentation 需要 ARM64 设备或模拟器；可选真模型测试需准备报告中指定的本机文件。`-PskipNative` 仅生成 Mock 原型。

[架构](docs/architecture.md) · [兼容性与限制](docs/compatibility.md) · [性能](docs/performance.md) · [全篇任务恢复](docs/reports/whole-document-recovery.md) · [小模型研究](docs/reports/mid-small-models.md) · [本次验证](docs/reports/alpha11.md)。

</details>

当前仍为开发预览版，持续负载、无障碍、手写笔、复杂 PDF 和学术翻译质量仍需更多设备验证。欢迎[反馈问题](https://github.com/kejie-tang/pararead-android/issues)，附上设备、Android/WebView 版本、模型及复现步骤；性能问题可附测速 JSON。

## 致谢与许可

基于 Chuanyunux 的 **[ParaRead](https://github.com/Chuanyunux/pararead)**；其声明感谢 Mathematic Inc 的 **vscode-pdf** 与 **Mozilla PDF.js**。本地推理使用 **llama.cpp / ggml**、Arm KleidiAI 和可选 Vulkan。本适配增加 Android 宿主、文件存储、翻译调度、模型管理与 JNI 集成；启动图标为本项目原创。

原创 Android 代码、适配修改及原创图形采用 **[Apache-2.0](LICENSE)**。第三方组件与单独下载的模型保留各自许可；分发时保留 [NOTICE](NOTICE)、[第三方声明](THIRD_PARTY_NOTICES.md)及[许可原文](third_party/)。App 许可证不授予任意 PDF、模型或译文的使用权。

本项目独立且非官方，不代表上游或模型提供方背书。PocketPal 等项目仅作行为与架构参考，未复制其代码或资产。[来源与许可核对](docs/licenses.md)。
