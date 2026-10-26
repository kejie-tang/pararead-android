# 参考项目与引入边界

核对日期：2026-10-25。以下是源码取得时的项目声明及本项目的引入策略，不把项目根许可证等同于每个依赖和资产的许可证。固定提交、根许可证文件和 Git blob 信息见 [references.json](research/references.json)。

| 项目 | 核对提交 | 根声明 | 当前使用范围 |
| --- | --- | --- | --- |
| [ParaRead](https://github.com/Chuanyunux/pararead/blob/c9d103481e4e3fa065cee6a52e611b8be20866c6/LICENSE) | c9d1034 | Apache-2.0；另有 NOTICE | 已复制固定 TS 阅读模块，并在构建时引入 PDF.js/样式/字体/WASM 资产；保留原声明与 Android 修改记录 |
| [llama.cpp](https://github.com/ggml-org/llama.cpp/blob/2280f0fd163b03d4331d49e08460f66502e35854/LICENSE) | 2280f0f | MIT | 已固定 405 个 CPU 构建 blob，编入 ARM64 llama.cpp/ggml；JNI wrapper 为本项目实现 |
| [PocketPal](https://github.com/a-ghorbani/pocketpal-ai/blob/4ef1600cc9062984cf69e2f1974e881e0b10e990/LICENSE) | 4ef1600 | MIT | 参考 React Native + llama.rn 的模型管理、移动推理和基准设计；不采用其 UI 技术栈，未复制代码 |
| [Readest](https://github.com/readest/readest/blob/32dc7856f1cd2b2c1b3abb2347b8221193b42b73/LICENSE) | 32dc785 | AGPL-3.0 声明 | 仅参考公开阅读/双栏/进度行为；未复制代码、样式、图标或测试 |
| [MJ PDF](https://github.com/mudlej/mj_pdf/blob/a5065a4d77c6e7bf07b134579752380005406b16/LICENSE) | a5065a4 | GPL-3.0 声明 | 仅参考公开文件导入、阅读与保存体验；未复制代码、资源或测试 |
| [PocketLLM](https://github.com/Kimi-syl/PocketLLM/tree/94ac4bbeba3131c58029389a73fda1d4ae442aaa) | 94ac4bb | API 未检出根许可证，/license 返回 404 | 仅参考公开的 Android JNI 与 OpenAI 兼容服务行为；未获明确许可前不引入代码或资产 |

ParaRead 才是本次逐模块审查与执行的主体。其他参考项目主要核对 README、许可证与架构线索；llama.android 另检查固定提交的 native/单线程封装公开源码。没有宣称对这些项目完成全面代码审计。

ParaRead 的 NOTICE 记录其来自 Mathematic Inc 的 vscode-pdf，并包含 Mozilla PDF.js。本次复制保留 LICENSE、NOTICE、文件头与原修改说明，third_party/pararead/NOTICE 追加 Android 修改记录和来源提交；原审查快照保持不变。

PDF.js 资产还包含单独许可证：CMaps、Foxit 字体、Liberation 的 SIL Open Font License、ICC 的 CC0，以及 JBIG2/OpenJPEG/QCMS 等 WASM 及包装层声明。已经在固定快照中保留对应文件；不能只带项目根 Apache 文本就删掉这些资产许可证。内置示例现为项目自编的六页阅读指南（Apache-2.0，见 samples/NOTICE）；Android 构建排除上游目录中的全部 PDF。根 [THIRD_PARTY_NOTICES.md](../THIRD_PARTY_NOTICES.md) 已整理直接引入范围，并将 11 份 PDF.js 许可逐字复制到 third_party/pdfjs/，路径/摘要见其 SOURCES.json。正式 Release 仍需按实际解析结果复核传递/原生依赖。

[Qwen3-1.7B-GGUF 官方仓库](https://huggingface.co/Qwen/Qwen3-1.7B-GGUF) 声明 Apache-2.0。本次复用现有 Ollama `qwen3:1.7b` Q4_K_M GGUF，Ollama 模型声明为 Apache-2.0，记录其实际 SHA-256/大小/元数据于 reports/local-qwen3-model.json；权重仅保存在本机忽略目录，不随 APK/Git 分发。未声明此文件等同某个官方仓库 revision。原始论文、用户 PDF 和提取文本不提交到版本库。

新增一键下载直接请求 [Ollama qwen3:1.7b](https://ollama.com/library/qwen3:1.7b) 的官方 registry 模型层，以固定摘要和字节数校验，元数据见 reports/default-model-source.json。APK 包含模型目录配置，不包含权重；下载由用户主动点击触发。模型仍为 Apache-2.0，未引入其他移动应用的模型管理代码。

用户已经明确不允许未经许可复制受约束代码，因此 Readest、MJ PDF 和许可不明的 PocketLLM 采用行为参考边界。后续若另选增量 PDF writer、第三方 JNI wrapper 或资产，要逐项核对再引入；不因为根项目允许使用就省略依赖核查。

## 本次 APK 的通知

根 LICENSE/NOTICE 为 Apache-2.0 项目声明及继承说明。third_party/pararead 保存原声明；third_party/llama.cpp 保存 MIT、llamafile MIT、xxHash BSD-2-Clause、rotate-bits MIT、SHA 公共领域声明。原固定源码中的 vendor 通知没有删除；未启用 common/server/mtmd 等目标。

构建将 third_party 复制到 APK 的 reader/licenses/，PDF.js 自带资产许可证继续位于 reader/pdfjs/ 下。运行时 Kotlin、AndroidX/Compose/Room、协程、OkHttp/Okio 采用 Apache-2.0，运行依赖清单见 reports/android-runtime-dependencies.txt；META-INF 的相关许可证采用合并保留。发布版仍需对实际使用的完整依赖和模型文件做一次发布清单复核，本次没有分发模型。

## 公开仓库准备补充（2026-10-26）

推荐仓库名 pararead-android，README 使用“非官方 Android 平板移植与扩展”的来源说明。保留原 Apache-2.0 LICENSE；根 NOTICE 逐字包含原 ParaRead NOTICE 后，再列出 Android 修改，修改过的 TS 和生成的 PDF.js 文件也补有文件内说明。项目总声明、LICENSE/NOTICE 与第三方原文一起打入 reader/licenses/。

另已从默认模型的实际 Ollama manifest license layer 获取并校验 Apache-2.0 许可全文，保留其 Copyright 2024 Alibaba Cloud 原署名，存于 third_party/qwen3/。这与 Qwen 官方 Hugging Face 仓库附带的版权年份不同，不据后者改写所选 layer 的声明；权重仍不随 Git/APK 分发。

公开仓库使用自编示例和重新生成的阅读器截图，移除包含旧论文内容的 Android 原型截图；历史测试报告保留原执行范围。Attention Is All You Need 只提供官方链接，不打包或附带全文。仓库名称、简介、许可边界与发布事项见 [publishing.md](publishing.md)。
