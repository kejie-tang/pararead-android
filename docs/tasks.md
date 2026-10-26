# 分阶段任务与剩余工作

`[x]` 为本次实现且有对应范围的证据；`[ ]` 包含未实现或未完成完整验收的事项，旁注区分。报告见 docs/reports/phase-2.md 至 phase-5.md。本次为集成开发版提交，不能把所有阶段的最终出口标记完成。

## Phase 1

- [x] 固定 ParaRead/PDF.js 源码、许可证、补丁与 493 个 blob；完成架构审查和兼容性基线。
- [x] 未修改上游的检查/构建/87 项测试通过、3 跳过；16 项迁移契约测试。

## Phase 2

- [x] P2.1 Kotlin/Compose 项目、wrapper、固定依赖；JDK21/API36/ARM64 APK 构建。
- [x] P2.2 复用原 TS/PDF.js 与离线资产，保留许可证/NOTICE/worker/CSP。
- [x] P2.3 SAF 流式快照、Long positional range endpoint、PDFDataRangeTransport。
- [x] P2.4 协议 v1、session/修订/请求关联、UTF-8 大小校验与错误/超时。
- [x] P2.5 双栏、滚动、句子 tap/hover、双向定位、高亮、同高与连接线；浏览器及模拟器证据。
- [x] P2.6 Mock、自动范围设置、主动句子优先、离页取消；故障/重试为独立 provider 测试。
- [ ] P2.7 多 DPI、缩放/旋转/跨淘汰页、长按/pinch/滑动冲突完整设备矩阵（部分浏览器已测）。
- [x] P2.8 APK、unit/instrumentation、截图与 Phase 2 报告；明确真机未测。

## Phase 3a：API

- [x] P3a.1 TranslationProvider/Flow、配置指纹、统一验证和调度。
- [x] P3a.2 Kotlin OpenAI 兼容端点、模型、key、超时及显式选择。
- [x] P3a.3 SSE/UTF-8 分包、finish_reason/[DONE]、有界输出及 HTTP 取消。
- [x] P3a.4 程序局部 ID、严格数量/重复/未知/空白/保护片段验证。
- [x] P3a.5 原版术语 JSON/语言覆盖/auto 与导入导出实现（文件 UI 待设备复核）。
- [x] P3a.6 有限重试、失败单句修复；草稿不入库，commit 后显示。
- [ ] P3a.7 真实 llama-server 经共用核心的翻译/结构/缓存/取消测试通过；真实论文人工语义质量与更多失败矩阵仍待验收。

## Phase 3b：JNI

- [x] P3b.1 llama.cpp 405 blob 固定校验、NDK/CMake ARM64 CPU 构建；模拟器真实 JNI 加载。
- [x] P3b.2 GGUF SAF 导入、分块 SHA/私有 mmap 路径、架构拒绝、加载/卸载代码。
- [ ] P3b.3 单线程所有权、native abort、UTF-8/背压已实现；真实模型资源/取消压力待验收。
- [ ] P3b.4 Qwen3 no-thinking、tokenizer 预算及 GBNF 已实现；本机 Q4_K_M 真实输出测试通过，量化对照、语义质量和大批次边界仍待验收。
- [x] P3b.5 串行推理、可配批次/字符、超长句内部片段与原 UI ID 保持。
- [x] P3b.7 默认 qwen3:1.7b 一键下载、系统队列恢复、进度/取消/重试、SHA 校验、删除；关闭思考。模拟器完整同哈希 HTTP 模型测试通过，官方 CDN 全文件与实体设备仍未验收，见模型管理报告。
- [ ] P3b.6 **真实 Qwen3-1.7B GGUF、ARM64 实体平板飞行模式、性能/质量/取消后续跑**。

## Phase 4

- [ ] P4.1 Room v1 表/导出 schema 已实现；历史迁移与 VS Code 缓存导入未实现。
- [ ] P4.2 优先级/抢占恢复/离页取消/有界全篇、持久游标恢复及成功/含失败句页计数已实现；共享订阅合并、缓存命中及句子级分类统计未实现。
- [x] P4.2a 原生持久全篇游标、逐页确认、取消后手动继续、同修订/配置自动恢复；Mock 模拟器 Activity 重建/部分页缓存复用通过，见全篇恢复报告；强杀/真模型持续任务仍待验收。
- [ ] P4.3 页/DOM/canvas/文本预算已实现；100 页/全篇后内存收敛完整压力待测。
- [ ] P4.4 阅读状态持久恢复/新会话已实现；全部字段、强杀、真模型重开 0 调用待设备验收。
- [ ] P4.5 G1 稀疏 byte-store 已实现，三档合成文件成功；复杂 parser/xref/object-stream 与真实文档回归未关闭。
- [ ] P4.6 本地指标、JSON、app+renderer PSS 已实现；CSV/Perfetto/Macrobenchmark/峰值采样未实现。
- [ ] P4.7 三档合成文字 PDF 模拟器首屏/跳页/PSS 已测；真实文件、实体平板、多次分位数/真模型耗时待测。

## Phase 5

- [ ] P5.1 PDF.js 批注 UI、增量草稿、文字重开已验证；永久高亮/ink/旧批注/实体笔完整验证待测。
- [ ] P5.2 G2 已实现无全文复制的 PDF.js 增量 writer；复杂加密/xref/对象流完整回归开放。
- [ ] P5.3 SAF 真 PDF 副本、fsync/失败保持 dirty 已实现；三档合成 PDF 原生增量保存及 pypdf/Poppler 复核通过，SAF 文件选择全流程待测。
- [ ] P5.4 横竖屏双栏/触摸分隔条/鼠标笔 hover 实现；TalkBack/实体笔/橡皮/多窗口完整矩阵未完成。
- [ ] P5.5 缺模型/输入错误、HTTP 断流、rendererGone 恢复路径已实现；磁盘不足/权限撤销/损坏模型/迁移/卡住 watchdog 完整测试未完成。
- [ ] P5.6 双栏块排序、公式保护、结构验证已测；复杂论文/跨页拆句/真实学术质量人工基准未完成。
- [x] P5.7 提供开发 APK、构建说明、第三方通知、自动测试、逐项状态与四阶段报告；实体硬件报告明确未测。

下一验收顺序：实体 ARM64 平板离线推理/取消/质量 → 真实 100/300/500MB 论文与大文件批注保存 → 真模型全篇强杀恢复/旧缓存迁移/无障碍与硬件矩阵。默认下载继续使用固定 SHA 的 Ollama Qwen3-1.7B GGUF；更换来源或量化需要独立验证。当前产物是可运行开发版，不宣称最终发布验收通过。
