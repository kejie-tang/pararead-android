# 大 PDF 性能验证方案

状态：以下为完整验收方案。已补充约 100/300/500MiB 合成文字 PDF 的 ARM64 API36 模拟器首屏、跳页和 PSS 单次测试，见 [Phase 4 实测报告](reports/phase-4.md) 与 [JSON](reports/android-large-pdf.json)。真实文件、实体平板、真实模型、多次分位数和保存峰值仍未测。

## 输入与运行矩阵

记录真实字节数（MB 使用十进制 1,000,000 字节，另外记录 MiB）、SHA-256、页数、文字/图像比例、线性化状态、xref 类型、字体、单双栏和授权来源。每个大小档至少一个合法文字型 PDF；优先真实长文档，可补充生成的有效文字内容测试文件，禁止仅填零或附加无关字节伪装工作负载。未获再分发许可的论文仅保存在忽略的本地目录。

| 维度 | 必测值 |
| --- | --- |
| 大小档 | 100MB、300MB、500MB（记录实际大小和偏差） |
| 文件布局 | 单栏、双栏、尾部 xref；至少覆盖非线性化 PDF |
| 文件来源 | 本地可 seek SAF；不可 seek provider 的流式复制回退 |
| 缓存 | 首次导入/提取/翻译；全部已缓存后重开；部分缓存后恢复 |
| 模型 | Mock；本地兼容 API；APK 内 Qwen3-1.7B 指定量化与 SHA |
| 设备 | 至少 1 台 ARM64 真平板；尽可能覆盖低/高 RAM；具体设备待记录 |
| 系统 | Android/安全补丁/WebView 版本、屏幕分辨率、DPR、方向、可用存储 |

固定构建类型和模型配置，记录温度、CPU 线程、上下文长度、GPU offload、当前电量和热状态。冷启动至少 5 次，热启动/跳页至少 20 次；短样本保留全部原始点，p95 仅描述样本，不伪装成稳定总体分位数。RAM 文件缓存是否已清空单独记录，force-stop 不等于冷磁盘缓存。

## 指标定义

| 字段 | 起止与单位 |
| --- | --- |
| appStartupMs | Macrobenchmark/系统启动起点 → Compose 首个可交互帧 |
| importMs / copiedBytes | SAF 选择完成 → 文件具备随机访问能力；单独记录复制开销 |
| firstPageRenderMs | 文档 session.open → 目标页的首次 pagerendered 且可见；另记 textLayerReady |
| jumpPageMs | 用户发出跳页 → 目标页可见且渲染完成；目标包括 1、中间、末页和随机 20 页 |
| selectionLinkMs | 点击缓存句子 → 双侧正确高亮且同高稳定；不混入模型耗时 |
| pssAppKiB / pssRendererKiB / pssTotalKiB | app 和每个相关 WebView renderer 的 PSS 分别采样后汇总；共享 renderer 的 PID 去重 |
| jsHeapBytes / arrayBufferBytes | 若 WebView 调试接口可获得则单列；无法取得时记 unavailable，不填 0 |
| nativeHeapKiB / canvasBytes | 原生堆、估算 canvas 像素/纹理预算；不是 PSS 替代指标 |
| modelLoadMs / firstTokenMs / tokensPerSecond | 加载、排队/前填充和 decode 分别计时；token 数来自对应 tokenizer |
| queueWaitMs / translationMs | 任务入队→开始；开始→句子校验并 DB 提交；API 与 JNI 分开 |
| rangeRequests / bytesRead / maxReadBytes | 随机访问次数、累计字节、单次最大字节、并发数；验证无整文件读取捷径 |
| cacheHits / modelCalls | 重开已提交句子命中情况；无失效条件时 modelCalls 必须为 0 |
| cancelledToStoppedMs | 用户取消 → HTTP/native 工作实际停止并确认；不能只测进度条消失 |

所有持续时间用单调时钟。JS 与 Kotlin 单调时钟原点不同，握手做 offset/往返时间估算；最终精确跨进程时间用平台 trace，不能直接相减 Date.now 与 elapsedRealtime。每个运行有 runId，事件关联 documentRevision/请求 ID，但导出不带论文内容、密钥或私人文件路径。

## 场景

1. 冷启动、导入、第一页可读，记录首次渲染前实际文件读取量。
2. 跳中间、末页及固定随机序列；缩放/旋转后重复，观察坐标与 canvas 回收。
3. 连续阅读至少 100 页后回到第一页；检查文本、译文 DOM、位图和 JS backing store 是否持续增长。
4. 当前页翻译未完成时跳 100 页，随后点一句；记录队列优先级、旧请求取消和 UI 过期消息。
5. 全篇翻译中插入主动选句、取消、后台再前台；确认整篇 job 与 viewport 生命周期分离。
6. 已提交部分译文后强制结束进程，重开从缓存读取；未完成项可恢复，成功项不重译。
7. 三个大小档分别测试高亮/文字/墨迹保存；重新打开和其他 PDF 阅读器检查内容，记录保存峰值 PSS。
8. 模型运行同时滚动 PDF；记录长任务/帧延迟/热降频，不能只测模型空闲时的阅读性能。
9. 磁盘不足、SAF 授权撤销、损坏/加密 PDF、renderer 被杀和模型加载失败后验证恢复。

## 工具与门槛

本版已提供本地 JSON 与 adb/CDP 基准；Android Macrobenchmark、Perfetto trace 和 CSV 导出仍为待办。完整验收计划要求：PSS 使用 Android MemoryInfo 或 `adb shell dumpsys meminfo <pid>`，按已确认的 app/renderer PID 每秒采样，并在翻译和保存边界加采样；不能只采宿主 Java heap。worker 通常处于 renderer 内，不另计一个虚构进程。

合格的硬门槛：无 OOM/ANR、无丢批注/译文、单次 range 响应不超过配置、缓存/DOM 遵守预算、离页取消有效、重开已提交句子不触发模型调用。严格大 PDF 内存门槛还要求关闭 architecture.md ADR-04 的全长 JS backing store 和 ADR-05 的全量保存问题；仅“不崩溃”不足以将它们报为通过。

启动/首帧/跳页延迟的产品数值目标在首台基准设备确定后固定；当前不伪造普适的毫秒或总 PSS 指标。基准报告必须同时给设备条件、目标和实测，超过目标按失败记录。500MB 未达标时明确标注未通过，仍保留其他档位的真实结果。

每次测试保存：`run.json`（构建/设备/模型/文件元数据）、`events.jsonl`、`summary.csv`、trace、必要截图，以及失败/跳过原因。当前仅有 JSON 单次合成模拟器数据和文件 SHA，未取得完整 trace/events/分位数矩阵。
