# Third-party notices / 第三方声明

核对日期：2026-10-27。本文件区分直接复用的代码与资产、运行依赖、单独下载的模型及仅参考的项目。项目新增代码与适配修改使用根目录 [Apache-2.0 LICENSE](LICENSE)；下列第三方内容保留自己的权利与条件。

## ParaRead、vscode-pdf 与 PDF.js

- **ParaRead 0.6.1**：Copyright 2026 Chuanyunux，Apache-2.0；来源 [Chuanyunux/pararead](https://github.com/Chuanyunux/pararead)，固定提交 `c9d103481e4e3fa065cee6a52e611b8be20866c6`。
- ParaRead 的 NOTICE 声明其基于 **vscode-pdf**，Copyright 2021 Mathematic Inc，Apache-2.0，并包含 **Mozilla PDF.js**。
- 本项目复用 TypeScript 阅读模块、消息/语言定义、样式及 PDF.js 集成，新增 Android 宿主和适配。修改的 TS 文件、生成的阅读器 bundle、HTML、PDF.js worker/viewer 均带修改说明。
- 原始许可与归属保存在 [third_party/pararead/LICENSE](third_party/pararead/LICENSE)、[third_party/pararead/NOTICE](third_party/pararead/NOTICE) 和根目录 [NOTICE](NOTICE)。固定源码文件清单及 blob 哈希见 [upstream/pararead.lock.json](upstream/pararead.lock.json)。

PDF.js 版本为 **6.2.108**，随固定 ParaRead 快照取得。其独立资产许可已逐字复制到 `third_party/pdfjs/`，并记录 [原始路径和 SHA-256](third_party/pdfjs/SOURCES.json)。原文件也继续随 PDF.js 资产打包：

| 组件 | 所保留的许可文件 |
| --- | --- |
| PDF.js | [Apache-2.0](third_party/pdfjs/LICENSE) |
| Adobe CMaps | [Adobe 的 CMap 版权、分发条款与免责声明](third_party/pdfjs/web/cmaps/LICENSE) |
| Foxit/PDFium 标准字体 | [PDFium Authors 的 BSD 条款](third_party/pdfjs/web/standard_fonts/LICENSE_FOXIT) |
| Liberation 字体 | [SIL Open Font License 1.1，含保留字体名](third_party/pdfjs/web/standard_fonts/LICENSE_LIBERATION) |
| ICC profile | [CC0 1.0](third_party/pdfjs/web/iccs/LICENSE) |
| JBIG2 解码器 | [PDFium BSD 条款及 Apache 文本](third_party/pdfjs/web/wasm/LICENSE_JBIG2)、[PDF.js wrapper Apache-2.0](third_party/pdfjs/web/wasm/LICENSE_PDFJS_JBIG2) |
| OpenJPEG | [BSD-2-Clause](third_party/pdfjs/web/wasm/LICENSE_OPENJPEG)、[PDF.js wrapper BSD-2-Clause](third_party/pdfjs/web/wasm/LICENSE_PDFJS_OPENJPEG) |
| QCMS | [MIT](third_party/pdfjs/web/wasm/LICENSE_QCMS)、[PDF.js wrapper BSD-2-Clause](third_party/pdfjs/web/wasm/LICENSE_PDFJS_QCMS) |

以上各原文中的作者、版权年份、保留字体名与免责声明均保留。项目根许可证不替代这些条款。

## llama.cpp / ggml 与原生后端

来源 [ggml-org/llama.cpp](https://github.com/ggml-org/llama.cpp)，固定提交 `2280f0fd163b03d4331d49e08460f66502e35854`。Copyright (c) 2023–2026 The ggml authors，使用 [MIT License](third_party/llama.cpp/LICENSE)。固定 CPU/Vulkan 构建文件见 [upstream/llama.lock.json](upstream/llama.lock.json)。JNI wrapper 为本项目实现。

已保留其内部组件声明：

- [llamafile CPU multiplication，Mozilla Foundation，MIT](third_party/llama.cpp/llamafile-MIT.txt)。
- [xxHash，BSD-2-Clause](third_party/llama.cpp/vendor/hash/xxhash/LICENSE)。
- [rotate-bits，MIT](third_party/llama.cpp/vendor/hash/rotate-bits/LICENSE.md)。
- [SHA support 的 public-domain 声明](third_party/llama.cpp/vendor/hash/sha256/LICENSE)。

范围见 [native NOTICE](third_party/llama.cpp/NOTICE)。当前 APK 包含按需加载的 Vulkan 后端，不启用 server、mtmd 等目标。

CPU 构建启用 [Arm KleidiAI v1.32.0](https://github.com/ARM-software/kleidiai/releases/tag/v1.32.0)，Copyright 2024–2026 Arm Limited and/or its affiliates，通过运行时 CPU 特征检测选择内核。固定下载摘要见 [upstream/kleidiai.lock.json](upstream/kleidiai.lock.json)；[Apache-2.0](third_party/kleidiai/Apache-2.0.txt) 与 [BSD-3-Clause](third_party/kleidiai/BSD-3-Clause.txt) 完整文本与包含 Fujitsu、Meta 贡献者署名的 [NOTICE](third_party/kleidiai/NOTICE) 随 APK 分发。


Vulkan 后端使用以下 Khronos 头文件；固定版本与下载摘要见 [upstream/vulkan.lock.json](upstream/vulkan.lock.json)：

- Vulkan-Headers v1.4.350，Copyright The Khronos Group Inc.，采用其双重授权中的 MIT 许可；[原声明](third_party/Vulkan-Headers/LICENSE.md)及[完整 MIT 文本](third_party/Vulkan-Headers/LICENSES/MIT.txt)随 APK 提供。
- SPIRV-Headers vulkan-sdk-1.4.350.0，Copyright The Khronos Group Inc.，编译使用的 SPIR-V 头文件采用 MIT；[完整上游声明](third_party/SPIRV-Headers/LICENSE)随 APK 提供。归档中的规范元数据另有许可，未作为运行时资产分发。
- glslc/shaderc 仅在构建机生成 SPIR-V；不随 APK 分发。设备 Vulkan 驱动由 Android/设备厂商提供。

## Android 运行依赖与开发工具

Kotlin、AndroidX（含 Compose、Room、WebKit、Lifecycle）、kotlinx.coroutines、OkHttp/Okio 等主要运行依赖使用 Apache-2.0。具体解析版本、约束与传递依赖见 [debugRuntimeClasspath 清单](docs/reports/android-runtime-dependencies.txt)；这份依赖树是版本证据，**不代替各 artifact 内的完整第三方通知**。APK 合并保留相关 META-INF 许可文本，正式 Release 仍需按其实际解析结果复核原生与传递依赖通知。

TypeScript（Apache-2.0）、esbuild（MIT）、Playwright（Apache-2.0）属于构建/测试工具，不作为这些工具的运行程序随 APK 分发。`org.json:json` 仅用于 JVM compileOnly/test；Android 运行时使用系统实现，不将 Maven 的 JSON-java JAR 打入 APK。开发依赖有各自的许可，不能用项目 Apache 标识概括。

## 默认下载的 Qwen3 模型

模型名称 `qwen3:1.7b`，Q4_K_M，来源为 [Ollama library/qwen3](https://ollama.com/library/qwen3:1.7b) 的固定模型层：

```text
SHA-256: 3d0b790534fe4b79525fc3692950408dca41171676ed7e21db57af5c65ef6ab6
Size:    1,359,279,776 bytes
```

**模型权重不随源码或 APK 分发**，由用户点击下载。对应 manifest 的 license layer 提供 Apache-2.0，原文附 `Copyright 2024 Alibaba Cloud`；该原文已保存在 [third_party/qwen3/LICENSE](third_party/qwen3/LICENSE)，license layer 摘要与来源见 [NOTICE](third_party/qwen3/NOTICE)。这里保留该文件实际署名与年份，不用其他模型仓库的许可文件替换。

[Qwen 官方 GGUF 仓库](https://huggingface.co/Qwen/Qwen3-1.7B-GGUF) 的声明也为 Apache-2.0；本项目不据此宣称所选 Ollama 权重与其任一文件字节相同。其他 GGUF、模型版本或第三方微调模型须分别查看自己的许可；本文件不重新授权用户导入的模型。

## 示例论文、用户文档与译文

Hugging Face 搜索展示各仓库模型卡声明的许可，模型权重仍由用户单独下载。LFM2.5 官方模型使用自定义 LFM1.0 许可；Qwen3.5 官方 GGUF 使用 Apache-2.0。应用代码的许可证不替代模型许可；分发或商用前应查看所选仓库的实际条款。

Alpha.4 的轻量快读候选为 [Unsloth/Qwen3-0.6B-GGUF](https://huggingface.co/unsloth/Qwen3-0.6B-GGUF) 的 `Qwen3-0.6B-Q4_K_M.gguf`，官方来源备选为 [Qwen/Qwen3-0.6B-GGUF](https://huggingface.co/Qwen/Qwen3-0.6B-GGUF) 的 `Qwen3-0.6B-Q8_0.gguf`；所测仓库模型卡声明 Apache-2.0。Qwen2.5 官方 Instruct GGUF 同样声明 Apache-2.0。精确仓库提交、文件大小、SHA-256 和各测试模型的许可标识见[模型清单](docs/reports/small-model-manifest.json)，兼容性与已知译文问题见[测试报告](docs/reports/small-model-evaluation.md)。这些可选权重不随源码、APK 或 Release 附件分发；默认 Ollama 权重的许可副本不用于代替其他模型仓库的实际许可文件。

内置 `reader/sample.pdf` 来自本仓库的 [samples/pararead-reading-guide.pdf](samples/pararead-reading-guide.pdf)，是本项目自编的六页操作示例，文字、表格、矢量图采用 Apache-2.0；署名与范围见 [samples/NOTICE](samples/NOTICE)。使用 PDF 标准基础字体，不嵌入外部字体文件。

Android 构建排除上游 PDF.js 目录中的全部 PDF，避免同时把原 tracemonkey 论文隐藏复制到 `reader/pdfjs/web/`。公开的阅读器截图由自编示例重新生成；旧论文的 Android 原型截图已移出公开源码。

Vaswani et al. 的 *Attention Is All You Need*（2017）仅在 README 提供 [arXiv 官方链接](https://arxiv.org/abs/1706.03762)，由用户自行下载并导入；源码、APK 和发布附件不包含该论文。该条目的 [arXiv 非独占分发许可](https://arxiv.org/licenses/nonexclusive-distrib/1.0/license.html)不等同于向本项目授予重新分发许可。

本仓库的代码许可证不替代用户 PDF、原文或生成译文的权利判断。

## 仅参考的项目与名称

PocketPal、Readest、MJ PDF、PocketLLM 用于行为、架构或线程策略研究，未复制其代码、样式、图标或测试资源。详见 [引入边界](docs/licenses.md)。这些名称的出现不表示它们作为运行依赖，也不表示任何项目作者为本项目背书。

ParaRead Android 是独立的非官方适配项目，与 ParaRead 原作者、Mathematic Inc、Mozilla、ggml、Alibaba Cloud/Qwen 或 Ollama 无官方隶属或背书关系。各项目名称和商标归其各自权利人所有。

## 源码及二进制中的通知位置

- Git 源码：根 `LICENSE`、`NOTICE`、本文件及 `third_party/`。
- 构建时：验证固定上游源，保留其原有许可证与文件头，并应用带修改说明的补丁。
- APK：`assets/reader/licenses/project/` 为项目许可与总声明，`assets/reader/licenses/` 含各第三方许可副本；`assets/reader/pdfjs/` 继续保留 PDF.js 原有资产许可证。

新增依赖、模型或资产时应同步更新其来源和声明，不能仅保留本次清单而把后续内容视为已核对。

## Android NDK C++ runtime

APK 随附 NDK 28.2.13676358 的 `libc++_shared.so`，供多个原生后端共享 C++ 运行时。LLVM / libc++ 采用 Apache-2.0 WITH LLVM-exception 等上游声明，完整 NDK 工具链通知保留于 [third_party/llvm/NOTICE.toolchain](third_party/llvm/NOTICE.toolchain) 并随 APK 分发；该通知还涵盖未打入 APK 的构建工具，不表示这些工具都作为运行时依赖。
