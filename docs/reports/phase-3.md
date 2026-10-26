# Phase 3 验证报告

日期：2026-10-26。交付：TranslationProvider、Mock、OpenAICompatibleProvider、LlamaCppProvider、验证器与调度均有实现并可构建。随后复用本机 Ollama GGUF，补齐真实 llama-server、macOS 共用 JNI 和 Android ARM64 模拟器原生推理测试，见 [补验说明](../local-llama.md)。**实体设备、真模型阅读器整页联动与学术语义质量未验收，本阶段完整出口保持开放。**

## API 与结构验证

Kotlin OkHttp 请求 `/v1/chat/completions`，支持 SSE/non-stream 兼容回复、UTF-8/event 分包、finish_reason/[DONE] 校验、超时和 Call.cancel；带 enable_thinking=false。HTTP 仅本地/私网，远端 HTTPS。API key 使用 Keystore AES-GCM，JS 不接触 key。

程序分配原句/局部 ID；拒绝缺失、重复、未知、空白、畸形 JSON、截断、think 污染、尾部多余 JSON 及保护片段不匹配。代码/公式/引用/数字单位/URL 使用批次内唯一掩码，术语表平铺条目与目标语言覆盖；设置支持语言、auto、批次和术语 JSON 导入导出。草稿解析只用于显示，完整验证后 commit Room，再通知 UI。失败最多 3 次尝试，失败批次单句修复；超长句内部片段合回原 UI ID。

## JNI

405 个固定 CPU 源码 blob 通过哈希验证。llama.cpp `2280f0fd163b03d4331d49e08460f66502e35854`，NDK 28.2.13676358/CMake 3.22.1，仅 ARM64 CPU，Release 优化，16KiB ELF 页对齐；MIT 及 ggml/供应商许可保留。

SAF 分块导入 GGUF、SHA-256、私有文件 mmap，拒绝非 qwen3 架构。上下文单线程所有权，atomic cancel/abort；Flow 等待实际执行退出，有背压 UTF-8 流；Qwen3 ChatML assistant 关闭 thinking 前缀和 /no_think。ctx4096、前填充实际 tokenizer 上限2400、生成1536；CPU 线程可在设置中选自动或手动，8 个可用核心时自动为 6。旧 4 线程基线保留，新增 [6 线程测试与设置报告](cpu-threads.md)。模型不随 APK 分发。

新增书库/设置一键下载默认 `qwen3:1.7b`（Q4_K_M），DownloadManager 后台任务、进度、取消、重试、摘要校验安装及删除。默认权重固定到与本机实测相同的 Ollama GGUF layer，关闭思考。完整功能与验证范围见 [模型管理](model-management.md)。

## 测试与边界

` :core:test` 共 **20 项通过**，见 [翻译 XML（13 项）](core-tests.xml)、[线程 XML（3 项）](cpu-threads-tests.xml) 与 [模型 XML（4 项）](model-files-tests.xml)：精确 ID/跨句占位符、尾部多余输出、重复拒绝、缓存指纹/重开零调用、UTF-8 分包和终止验证、无效/未完成结果拒绝、草稿/内部片段、有限重试不入库、离页取消、选句抢占后全篇恢复，以及线程策略、安装校验/取消/删除范围与默认关闭思考。MockWebServer 的协议回归之外，真实服务由共用核心类执行补验。

ARM64 instrumentation 已验证真实 GGUF 加载、中文生成、流式、取消后继续，最终复测在飞行模式下执行，详见 real-model-android.json；不是实体平板。JNI 加入 GBNF 约束输出对象形状，结构仍须 Kotlin 校验，模型加载无独立 watchdog。初始译文暴露过语义错误；没有人工学术质量分数、长时压力或实体硬件吞吐报告。

全篇任务逐页有界生产、支持取消，已补充持久游标自动恢复及成功/含失败句页数；见 [恢复报告](whole-document-recovery.md)。缓存命中及句子级分类计数、共享订阅合并未实现；重新执行会利用已提交内容缓存。真实模型持续全篇恢复仍待验收。
