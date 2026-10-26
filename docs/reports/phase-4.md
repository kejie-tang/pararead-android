# Phase 4 验证报告

日期：2026-10-26。随机分块、Room 译文/页坐标/阅读状态、有界优先级队列、页/DOM/canvas 缓存与本地指标已实现；完整大文件与真实模型验收仍有缺口。

## 三档合成文件实测

环境：Android API36 ARM64 模拟器，WebView133.0.6943.137、2560×1600@240dpi，Mock。使用有效非线性化、未压缩文字 PDF，每页80行全部在MediaBox内；不是填零/无关数据。
100/300/500 MiB 对应约105/314/524十进制MB，输入实际字节/SHA/页数见 large-fixtures.json。

| 目标MiB | 页数 | 首次渲染ms | 跳页ms | app+renderer PSS MiB | 增量草稿保存ms | suffix字节 | 基础结果 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| 100 | 3264 | 4067 | 438 | 474.2 | 318 | 959 | PASS |
| 300 | 9792 | 6838 | 731 | 590.1 | 259 | 970 | PASS |
| 500 | 16320 | 8862 | 464 | 559.2 | 310 | 970 | PASS |

原始值见 android-large-pdf.json、android-benchmark.txt。native-incremental-validation.json 验证三档增量 PDF 可读，检查对应页批注内容/AP，并以Poppler渲染。
pypdf非零起始xref提示保留在JSON/日志中，Poppler无警告。
基准采样 APK 的 SHA-256 为 `624b907a5c1206b39fdeef74ff6b4a9f125a31e0d7adbb370ddfc5b8ae67a130`；之后调整了工具栏响应式 CSS、翻译校验/占位符与 JNI grammar，并补充真实模型测试，未重采这组三档性能数值。读取及保存代码相同；该组仍是无模型的 Mock 基准。

首屏：adb发出Activity启动 → PDF第一页 renderingState=FINISHED 且canvas可见，包含debug入口复制/SHA/import、启动和CDP轮询。
每档进程force-stop，清除基准文档Room位置，禁用PDF.js独立历史恢复。本次基准从空应用数据开始，磁盘/系统缓存可能仍热；不是Macrobenchmark的系统冷启动数值。
跳到75%页，等待该页pagerendered，轮询粒度约100ms。每档最终保留一次样本，无p50/p95。

PSS为跳页后的app与受控环境中WebView renderer汇总，非峰值，未加载模型；mainJsHeap仅主页面CDP堆，不含worker总堆/全部ArrayBuffer。
草稿保存计时不含SAF目标文件的完整复制。

## 内存路径与回归

源字节稀疏LRU为64MiB，单个连续对象/合并range上限16MiB，原生响应≤256KiB，最多4个在途fetch。
PDF.js canvas buffer=4、每页4M pixel，TS页/DOM/状态按当前±5淘汰。3GiB稀疏源的Long读取/并发/尾部边界通过instrumentation。
译文先校验写Room再推送；核心测试验证重开缓存provider调用为0、离页取消、选句抢占后全篇恢复。

大文件保存复测曾发现原版Page.save并发遍历所有页导致500MiB renderer OOM。
现让编辑器批注仅经过实际编辑页的saveNewAnnotations；新增测试验证16320页仅访问一个目标页，三档复测与独立解析通过。
表单字段修改保留≤512页旧widget路径，超过阈值明确拒绝，不静默丢字段。

## 指标与剩余工作

android-metrics.json包含activity_first_frame_ms（Activity创建→Compose下一帧）、open_ms、first_render_ms、translation_ms、app_pss_kib等实际事件，UI可导出JSON。
JS、原生单调时钟与host基准时钟各自计时，不直接相减。本报告的translation_ms来自Mock/队列全过程；真实模型冒烟耗时另见 ../local-llama.md，不能替代大PDF与模型并行的性能验收。

未完成：真实论文和实体平板、模型同时推理、连续100页/全篇后内存收敛、全书worker对象缓存压力、复杂xref/object-stream/加密/损坏恢复、保存峰值及SAF全流程。
全篇持久游标、自动恢复、取消后手动继续及成功/含失败句页数已补充实现；Mock 模拟器重建与部分页缓存复用通过，见 [恢复报告](whole-document-recovery.md)。强杀/真实模型持续恢复、缓存命中及句子级分类计数仍待补充。
共享订阅、旧缓存/历史Room迁移、完整runId/CSV/Perfetto/Macrobenchmark与多次分位数亦未完成。
G1/G2的基础源码问题有修复和合成证据，完整兼容门槛仍开放。
