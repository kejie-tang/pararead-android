# ParaRead Android

面向 Android ARM64 平板的双语 PDF 阅读器，保留 ParaRead 的句子级原文/译文对齐，并提供基于 llama.cpp 的离线翻译。

*An unofficial Android tablet adaptation of ParaRead, with sentence-aligned bilingual PDF reading and offline translation via llama.cpp.*

> 这是基于 [Chuanyunux/ParaRead](https://github.com/Chuanyunux/pararead) 0.6.1 的独立、非官方 Android 移植与扩展，未获原项目或相关上游项目的官方背书。沿用名称是为了说明项目来源；上游的版权与第三方许可证均保留。

## 功能与定位

- 平板双栏：左侧 PDF、右侧结构化译文，支持句子级双向定位、高亮、同高对齐与同步滚动。
- 离线翻译：应用内 llama.cpp JNI 加载 Qwen3 GGUF；默认 `qwen3:1.7b` Q4_K_M，关闭思考，可配置 CPU 线程数。
- 模型管理：一键下载、大小/SHA-256 校验、进度、取消、重试、删除及手动 GGUF 导入，无需安装 Ollama。
- 按句/本页/邻页/全篇翻译，译文持久缓存，全篇进度保存与重开续跑。
- PDF 批注、本地增量草稿和另存 PDF；原文件采用分块随机读取与按页加载。
- 可显式选择 OpenAI 兼容 API；另有明确标注的 Mock 后端用于开发验证。

阅读器的 TypeScript/PDF.js 代码复用自 ParaRead；Android 宿主、SAF 文件访问、Room 缓存、翻译调度、模型管理和 JNI 集成为本项目的新增或适配。来源、修改范围及许可证见 [第三方声明](THIRD_PARTY_NOTICES.md)。

## 当前状态

**开发预览版（0.2.0-dev），尚未完成正式发布验收。** 已构建 ARM64 APK；24 项 Kotlin、13 项 JS/bridge 和 9 项 Android 模拟器回归通过，范围见 [验证元数据](docs/reports/verification.json)。

已用真实 Qwen3 GGUF 完成 llama-server、macOS 共用 JNI 和 Android ARM64 模拟器推理冒烟测试。APK 不内置模型权重。**实体平板、天玑 9400 性能、真实学术翻译质量及大文件完整设备矩阵仍未验收。** 三档合成 PDF 测试不能代替真实论文/长书与模型同时运行的测试。

首次下载模型需要联网。本地 JNI 翻译不向远端翻译服务发送原文；选择兼容 API 后，待翻译文本及翻译提示会发送到用户配置的服务。PDF、译文与阅读状态保存在本机。

## 运行

构建产物：`app/build/outputs/apk/debug/app-debug.apk`。本机另保存 `artifacts/ParaRead-android-arm64-debug.apk`；产物不提交 Git。Android 9 / API 28 起，ARM64，须使用支持 WebMessageListener 的 Android System WebView；实际验证为 API 36、WebView 133.0.6943.137。

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

1. 打开自编示例文档，或通过系统文件选择器导入文字型 PDF。
2. 点击书库的“下载默认模型”，或进入“设置 → 离线 GGUF”一键下载 **qwen3:1.7b（Q4_K_M，1.36 GB）**。下载通过 Android 系统队列在后台继续，重新打开可恢复进度；完整大小与 SHA-256 校验后自动启用，**思考模式默认关闭**。设置内可取消、重试、删除本机模型，也保留 GGUF 文件导入。下载校验约需 2.8 GB 可用空间；安装后仅保留一份约 1.36 GB 的模型。删除模型保留 PDF、阅读进度与译文缓存。见 [模型管理与测试](docs/reports/model-management.md)。
   CPU 推理线程数可设为 `0`（自动）或 `1–当前可用核心数`；识别到 8 核时自动使用 6 线程。保存后重建推理会话，已缓存译文继续复用。默认离线后端，不自动切换联网翻译服务。模型保存在应用私有目录；本版只接受 `qwen3` 架构。见 [线程设置与实测](docs/reports/cpu-threads.md)。
3. 没有模型时，可显式选择“模拟”验证阅读联动，或选择“兼容 API”配置 `/v1` 基址。设备内 llama-server 可用 `http://127.0.0.1:8080/v1`，模拟器访问宿主机可用 `http://10.0.2.2:8080/v1`；外网服务要求 HTTPS。
4. 点击原句/译文双向定位；工具栏可翻译本页、全篇、取消和另存批注 PDF。全篇逐页保存进度，重开同一文档/配置自动续跑；主动取消后点击“全篇 / 继续”接续。状态区分别显示已处理、成功和含失败句的页数；已完成任务再点全篇可重试失败句，缓存译文继续复用。见 [全篇恢复验证](docs/reports/whole-document-recovery.md)。自动翻译范围可选当前页、邻近页或手动。术语表兼容原版平铺 JSON 与目标语言覆盖，可导入/导出。
5. PDF 导入采用 256KiB 缓冲复制为私有不可变快照，需要额外磁盘空间。批注自动保存本地增量草稿；“另存”将原 PDF 与增量内容流式合并为真正的 PDF 副本。

## 示例与推荐阅读

推荐体验论文：Vaswani et al., *Attention Is All You Need*（2017）。请从 [arXiv 官方页面](https://arxiv.org/abs/1706.03762)下载 PDF 后导入。本仓库和 APK 不附带该论文，其版权及使用条件独立于本项目的 Apache-2.0 许可；当前 arXiv 版本使用 [arXiv 非独占分发许可](https://arxiv.org/licenses/nonexclusive-distrib/1.0/license.html)。

下图来自桌面 Chromium 的触控模拟，译文为明确标记的 Mock 输出，用于展示布局和联动：

![自编示例的双语阅读布局](docs/reports/reader-landscape.png)

## APK 下载与发布

仓库：[kejie-tang/pararead-android](https://github.com/kejie-tang/pararead-android)。

- [Actions / Android CI](https://github.com/kejie-tang/pararead-android/actions/workflows/android.yml)：通过后可在运行页面的 Artifacts 下载 `pararead-android-debug-arm64`，解压后安装 APK。需要登录 GitHub；产物保留 14 天。它使用临时 debug 签名，不作为持续升级渠道。
- [Releases](https://github.com/kejie-tang/pararead-android/releases)：用于保留带版本号、持久发布签名的 APK。仓库提供手动生成 Release 草稿的工作流，首次需配置签名 Secrets；完整步骤见 [发布指南](docs/publishing.md)。

## 构建与验证

Node.js 24+、Python 3.9+、JDK 21、Android SDK 36、NDK 28.2.13676358、CMake 3.22.1。Android Studio 打开项目根目录，Gradle JDK 选择 21，并配置 SDK。依赖版本见 Gradle 文件与锁定清单。

```sh
npm ci
python3 scripts/fetch_upstream.py
python3 scripts/fetch_llama.py
npm test
npm run check:reader
npm run test:android-web
./gradlew :core:test :app:assembleDebug :app:lintDebug
# 连接 ARM64 设备或模拟器后
./gradlew :app:connectedDebugAndroidTest
# Chromium 首次使用先安装
npx playwright install chromium
npm run test:reader
```

设置 `JAVA_HOME` 为 JDK 21，`ANDROID_HOME` 为 SDK 路径。本机可用 `scripts/gradle.sh` 自动选择已下载的本地 JDK；脚本不会自动下载 JDK。`-PskipNative` 仅用于不含 JNI 的 Mock 原型，不能用于离线模型验收。原始 ParaRead 与 llama.cpp 源码位于忽略的 `.references/`，下载文件逐个核验 Git blob 哈希。

```sh
python3 scripts/generate_large_pdf.py
node scripts/benchmark_android.mjs
# 可选独立校验（需 pypdf、Pillow、pdfinfo、pdftoppm）
python3 scripts/validate_android_saves.py
```

大文件脚本生成约 100/300/500 **MiB** 的有效文字 PDF（非填零占位）；基准会向连接的 debug 设备导入这些测试文件，使用 Mock 并重启本应用。约需 1GiB 以上主机空间和额外设备空间；测试细节见性能报告。

## 交付与限制

- [架构与实现边界](docs/architecture.md)、[行为兼容性](docs/compatibility.md)、[剩余任务](docs/tasks.md)、[实际消息协议](docs/bridge-protocol.md)。
- [Phase 1 审查](docs/reports/phase-1.md)、[Phase 2 原型](docs/reports/phase-2.md)、[Phase 3 翻译](docs/reports/phase-3.md)、[Phase 4 大文件](docs/reports/phase-4.md)、[Phase 5 批注与回归](docs/reports/phase-5.md)。
- [性能原始值](docs/reports/android-large-pdf.json)、[浏览器联动结果](docs/reports/android-reader-browser.json)、[构建/测试元数据](docs/reports/verification.json)、[许可证](docs/licenses.md)。

稀疏字节缓存上限 64MiB，单个连续对象/范围预算 16MiB，canvas 预算按页限制。超过512页的表单字段修改暂拒绝保存，编辑器批注没有该页数阈值。需要完整文件修复扫描的损坏 PDF、超大单对象和复杂保存兼容性仍待回归；此限制会显式报错。三档合成文件已通过模拟器首屏/跳页，不等于真实长书、峰值内存或模型并行推理已验收。旧 VS Code 缓存导入、全篇强杀/真模型持续恢复验收、完整无障碍和实体触控笔测试尚未完成。

## 开源许可与致谢

本项目的 Android 原创代码与适配修改采用 **[Apache License 2.0](LICENSE)**。第三方代码、字体、WASM、模型和文档保留各自的许可证及版权；根许可证不会将这些内容统一重新授权。

| 组件 | 来源与许可 | 本项目的使用 |
| --- | --- | --- |
| ParaRead 0.6.1 | [Chuanyunux/ParaRead](https://github.com/Chuanyunux/pararead)，Apache-2.0 | 复用并修改 TypeScript 阅读模块、消息、样式及其 PDF.js 集成；保留 [NOTICE](third_party/pararead/NOTICE) |
| vscode-pdf / PDF.js | Mathematic Inc / Mozilla，Apache-2.0 | 保留 ParaRead 的上游归属；PDF.js 的字体、CMap、ICC、解码器有独立许可，见 [第三方声明](THIRD_PARTY_NOTICES.md) |
| llama.cpp / ggml | The ggml authors，MIT | 固定 CPU 引擎源码，经本项目 JNI 集成；保留 MIT 与内部 vendor 声明 |
| Kotlin / AndroidX / Compose / Room / 协程 / OkHttp / Okio | 各上游项目的许可 | Android 运行依赖，完整解析记录见 [依赖清单](docs/reports/android-runtime-dependencies.txt) |
| 默认 Qwen3 GGUF | Alibaba Cloud 模型；所选 Ollama layer 提供 Apache-2.0 许可 | 权重单独下载，不随源码/APK 分发；保留 [模型许可及来源](third_party/qwen3/NOTICE) |

内置示例为本项目自编的六页 [ParaRead Reading Guide](samples/pararead-reading-guide.pdf)，文字、表格与矢量图采用 Apache-2.0，见 [示例声明](samples/NOTICE)。它是阅读操作示例，不是学术论文或性能报告。构建排除 PDF.js 上游快照中的全部 PDF；公开阅读器截图使用自编示例。用户导入的 PDF、其他模型及生成译文的权利不由本项目许可证授予。

PocketPal 的移动推理/线程策略、Readest、MJ PDF 与 PocketLLM 仅作行为或架构参考，未复制其代码或资源。各项目名称、商标和标识归原权利人所有。

详见 [NOTICE](NOTICE)、[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)、[来源与许可审查](docs/licenses.md)；仓库信息、版本、签名和 APK 发布步骤见 [仓库准备说明](docs/publishing.md)。
