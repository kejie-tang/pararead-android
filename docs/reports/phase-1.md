# Phase 1 验证报告

日期：2026-10-25，Asia/Shanghai。交付范围为源码审查、架构、兼容性清单、任务清单及可运行的审查工具；**不包含 Android 原型/APK，未进行 ARM64 真机测试**。

## 基线和环境

- ParaRead 0.6.1，`c9d103481e4e3fa065cee6a52e611b8be20866c6`，PDF.js `6.2.108@7d9b2e0095e602ad274f05bb02fbb4125b30d93f`。
- 493 个固定文件通过 Git blob 哈希校验，源文件无修改。仅排除演示 GIF，完整性清单在 `upstream/pararead.lock.json`。
- 本机 macOS，Node `v24.15.0`、pnpm 执行器 `11.25.0`、Python `3.9.6`；上游声明 pnpm `11.24.0`，依赖按原 frozen lockfile 安装，未改包版本，安装使用 `--ignore-scripts`。
- PATH 中 `java` 找不到运行时，但 Android Studio 内含 OpenJDK `25.0.2`。本机存在 Android SDK 34/35/36/37.0 与 NDK；尚未验证 Android Gradle 构建所需 JDK 组合。
- 使用 SDK 的 adb 检查，无连接设备。本次不把工具链目录存在视为 Android 构建或设备可用证明。

## 执行结果

| 范围 | 命令/实际步骤 | 结果 | 证据 |
| --- | --- | --- | --- |
| 源码完整性 | `python3 scripts/fetch_upstream.py --verify-only` | PASS：493/493 | [原始输出](upstream-integrity.txt) |
| 审查契约 | `npm test`；另保存 TAP 报告 | PASS：16 通过、0 失败、0 跳过 | [TAP](source-contracts.tap) |
| 上游类型/资源 | 原 `pnpm run check` 的前两步 | PASS：两套 tsc，113 个 locale 资产 | [首次输出](upstream-check.txt) |
| 上游完整检查首次运行 | `.references/pararead` 内 `pnpm run check` | FAIL：父目录 ignore 导致 oxlint 无匹配文件；未完成 format | [首次输出](upstream-check.txt) |
| 定位环境问题 | 显式源码范围 oxlint `--no-ignore` | PASS，exit 0；同目录 oxfmt 仍无匹配文件，exit 2 | [格式化诊断](upstream-format.txt) |
| 上游完整检查复核 | `python3 scripts/check_upstream.py`，在临时目录执行未改动的 `pnpm run check` | PASS：tsc、PDF.js assets、lint、70 个文件 format | [完整输出](upstream-check-isolated.txt) |
| 上游 Vitest | 实际执行 `pnpm test -- --reporter=json`，由 Vitest 默认文本 reporter 输出统计 | PASS：7 个文件；87 通过、3 跳过、0 失败 | [原始输出](upstream-test.txt) |
| 上游构建 | `pnpm run build` | PASS：browser ESM 与 VS Code CJS 均成功 | [原始输出](upstream-build.txt) |
| 原版 harness HTTP 烟测 | `node scripts/smoke_reader.mjs`，临时端口启动后自动关闭 | PASS：入口包含 Mock host，bundle/PDF.js/worker/样式/样本 PDF 共 5 个资源的 HTTP 和 MIME 正确 | [JSON](harness-smoke.json) |
| Android APK/Instrumentation | 未执行，Phase 2 尚未实现 | NOT IMPLEMENTED | 无 APK |
| 本地 API / JNI / GGUF | 未执行，Phase 3 尚未实现 | NOT IMPLEMENTED | 未下载模型 |
| 100/300/500MB 基准和保存 | 未执行 | NOT TESTED | 仅有 performance.md 测试方案 |

`check_upstream.py` 仅复制哈希清单中的文件、链接已安装依赖、运行原有命令并清理临时目录，不修改源文件、lint 配置或格式配置。首次失败及定位过程保留，不能将“没有检查任何文件”报成成功。

上游测试前置提取了 PDF.js 自带双栏论文。3 个跳过项属于 Attention 单栏论文的标题/章节、引文/公式切句及段落/页码测试，下载 `1706.03762v7` 在 60 秒超时后未能生成 fixture；这些用例没有通过验证。日志中的可选 `@napi-rs/canvas` 缺失警告不影响此次文本提取。harness 烟测只验证服务器入口与资源，不执行浏览器 JavaScript；本次没有验证 Node canvas、浏览器像素渲染、实际点击交互或 Android WebView。

## 契约测试含义

12 个用例检查分段 ID/坐标稳定、有序双栏输入、数字/URL 切句、术语覆盖、缓存身份和重开命中、批次 ID 映射、有限漏句重试、原文去重、JSON 基础验证、旧回复隔离和双侧共享选句状态。

另 4 个 `GAP` 用例确认：重复回复 ID 被覆盖、HTTP 请求非流式、换页不取消旧页请求、PDF.js 分块 stream 仍预分配整文件长度数组且保存请求完整源流。这些用例通过表示审查结论可复现，**没有修复这些功能**。测试使用合成文本和 Mock client，不是 Qwen 翻译质量或真实触摸行为测试。

## 架构审查结论

1. 可以以宿主适配方式保留原 TypeScript 阅读器：句子和块、坐标、结构化译文面板、双向选择、同高同步及连接线均已有可识别复用点。
2. Android 需要新增 SAF/随机读、版本化桥、Room、优先级/取消队列和三类 provider，不能直接运行 VS Code/Node 宿主。
3. 500MB 低内存要求存在明确底层门槛：PDF.js 全长数组和全量保存。架构 ADR-04/05 已记录验证和选择路径；尚未解决，不承诺已达标。
4. Readest/MJ PDF 只参考公开行为；PocketLLM 未检出根许可证，不引入代码。根许可证不能代替对 PDF.js 字体/WASM 等资产逐项保留声明。

Phase 1 的审查、设计、清单和验证工具交付已完成；Phase 2–5 的实现与验收保持未完成。下一开发入口是 tasks.md 的 P2.1–P2.4，再验证 Mock 下的逐句双向联动；大文件门槛继续单独跟踪。
