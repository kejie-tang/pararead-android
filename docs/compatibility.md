# 原版兼容性与实际验收

当前为 Phase 2–5 集成开发版。**真机栏全部未测**；ARM64 模拟器与桌面 Chromium 证据分别列出。实现不等于通过全部场景，所有原始必须项保留，未完成项不删除。

证据：A=[浏览器 JSON](reports/android-reader-browser.json)，B=[Android instrumentation](reports/android-instrumentation.xml)，C=[核心 JVM 测试](reports/core-tests.xml)，D=[大文件原始值](reports/android-large-pdf.json)，E=[源码/浏览器测试输出](reports/android-web-tests.txt)，F=[三档增量 PDF 独立复核](reports/native-incremental-validation.json)，G=[真实 GGUF 补验](local-llama.md)。构建与设备版本见 [元数据](reports/verification.json)。Phase 1 上游审查历史保留于 source-audit.md 与旧报告。

| ID | 必须行为 | 实现与已执行证据 | 剩余验收 |
| --- | --- | --- | --- |
| R01 | PDF 左、译文右 | 原版 DOM 双栏；A、模拟器截图 | 实体平板 |
| R02 | 程序管理句子 ID、一一对应 | 原分段器 + 局部批次映射；C、上游契约 16 项 | 真模型输出统计 |
| R03 | 双侧高亮 | 原 overlay/selection/panel；A、B | 多 DPI/旋转矩阵 |
| R04 | 点原文找译文 | 触摸点句后激活译文；A | 无缓存下真实模型延迟 |
| R05 | 点译文找原文 | panel 激活及 PDF 定位；A、B | 跨淘汰页/复杂缩放 |
| R06 | 同高对齐、连接线 | 保留原 scroll-sync/connector；A，误差门槛 ≤4 CSS px | 各缩放/边界 clamp/设备 |
| R07 | 阅读位置同步译文 | 保留 30% 锚点、1500ms 手滚暂停 | 连续/快速滑动完整自动回归 |
| R08 | 标题/段落/列表/图表标题/代码结构 | 原 role 与块内句子 span 保留；源码、截图 | 分类 fixture 全覆盖；不声称重建图中文字 |
| R09 | 双栏及阅读顺序 | 原启发式 + 保守块空间排序；E | 行级交错流、跨栏图/表/复杂真实论文仍有限制 |
| R10 | 局部优先翻译 | 点句升级 pending 所有权；抢占后旧任务恢复；C、E | 真模型响应与取消延迟 |
| R11 | 页/邻页/manual | 设置可选，默认当前 ±1，边界裁剪 | 设备上的范围/页边界矩阵 |
| R12 | 全篇/取消/进度 | 逐页有界、原生持久游标与确认、同配置自动恢复、取消后手动继续；已处理/成功/含失败句页计数；[恢复报告](reports/whole-document-recovery.md) | 真模型/强杀/持续大文件恢复；缓存命中及句子级分类计数未实现 |
| R13 | PDF 批注/永久高亮 | 保留 PDF.js 高亮/文字/墨迹 UI；文字批注持久恢复 B | 永久高亮、笔迹、旧批注、实体笔完整复核 |
| R14 | 真正 PDF 保存 | 增量 suffix + 原生流式合并；A 重解析、B 草稿重开、F 三档 pypdf/Poppler 复核 | SAF 文件选择全流程、加密/复杂 xref 保存 |
| R15 | 阅读进度恢复 | Room 保存页/hash/scale/rotation/选句/栏；重建路径 B | 进程强杀、全部字段/权限丢失矩阵 |
| R16 | 触屏替代 hover | 短 tap、移动/长按/multi-pointer/cancel 保护；A | pinch/文本长按/滑动冲突设备矩阵 |
| R17 | 鼠标/笔悬停 | 非 touch 且无按键时 250ms hover，编辑模式暂停 | 实体鼠标/有 hover 的笔均未测 |
| R18 | 术语表/语言 | 平铺 + 目标语言覆盖、auto、JSON 导入/导出；C/契约 | 文件选择 UI、目标语言人工翻译质量 |
| R19 | 横竖屏/分隔条 | Compose 尺寸适配、双栏拖条；A 横竖截图 | 实体平板/分屏/比例恢复完整矩阵 |

R06 保留选句/阅读锚点的同高语义，不声称所有双语句子能同时等高。R09 不以块排序冒充通用 PDF 版面分析。

| ID | 大文件/缓存要求 | 实现与证据 | 剩余验收 |
| --- | --- | --- | --- |
| D01 | JS 不全文加载 PDF | 稀疏源缓存 64MiB，GetData 禁止；E、D 三档渲染 | G1 广泛 parser 回归未关闭；16MiB 单对象预算/损坏全文修复限制 |
| D02 | SAF/随机访问/分块 | 全输入流式私有快照，256KiB positional Long 读；B 含 3GiB sparse/并发/尾部边界 | 云 provider、空间不足、真实 SAF 压力 |
| D03 | 懒加载与位图预算 | PDF.js renderingQueue，4 页 buffer、4M pixel/canvas | 100 页遍历/峰值纹理与 PSS 收敛 |
| D04 | 按页坐标提取/缓存 | Room PageRecord、±5 页 TS/DOM 淘汰 | >60KB 缓存回复暂重提取；超大页显式拒绝缓存 |
| D05 | 只预翻译必要邻页 | page/nearby/manual，按优先级有界 | 真实模型翻页压力 |
| D06 | 离页取消过期任务 | epoch/cancel/abort；C，旧 UI 请求所有权 E | 共享订阅合并未实现；实际 JNI 取消待测 |
| D07 | 及时写库、重开不重译 | 校验 → commit → UI；C 验证重开调用 0，B Room 有记录 | 真模型/强杀设备验收 |
| D08 | 100/300/500MB 测试 | 约 100/300/500MiB 有效合成文字 PDF，ARM64 模拟器 D，增量保存 F | 真实论文、实体平板、SAF 另存/模型并行 |
| D09 | 性能指标 | 本地 open/first_render/translation/PSS 事件，JSON 导出；D app+renderer PSS | PSS 峰值、冷/热多次 p50/p95、Perfetto/Macrobenchmark、真模型耗时未完成 |
| D10 | 配置/模型缓存隔离 | 内容+GGUF SHA/端点+语言+术语+版本指纹；C | VS Code 旧缓存导入与来源未实现 |

| ID | 翻译要求 | 实现与证据 | 剩余验收 |
| --- | --- | --- | --- |
| T01 | Mock 联动 | MockTranslationProvider、A/B 双向选择；C 取消/缓存/抢占 | 更多故障注入 UI 场景 |
| T02 | OpenAI 兼容 API | Kotlin HTTP/SSE，MockWebServer C + 真实 llama-server 翻译/取消/缓存 G | WebView 真模型整页联动、其他服务与长时压力 |
| T03 | APK 内 GGUF 离线 | 固定 ARM64 CPU 引擎编入 APK，真实 Q4_K_M 在 ARM64 模拟器飞行模式通过 G；SAF 模型导入已实现 | **实体平板、SAF 模型导入界面、真模型阅读器整页翻译未验收** |
| T04 | 无 thinking/流式/取消 | Qwen3 模板前缀、/no_think、API enable_thinking=false；UTF-8/背压/abort；真实生成及取消后继续 G | 长时取消资源压力、加载 watchdog 未实现 |
| T05 | 精确结构校验 | 数量/重复/未知/遗漏/空白/截断/think/尾部拒绝；C，GBNF 与真实漏占位符拒绝/有限修复 G | 真实论文大样本合法率与失败统计 |
| T06 | 公式/引用/数字/单位/URL/术语/代码 | 掩码精确恢复、术语 prompt 覆盖；C | 术语/语义、其他公式格式和学术人工评分；不保证仅靠掩码能确保全部质量 |
| T07 | 批次、超长句、有限重试 | 1–40 句设置、字符预算、实际 tokenizer 预算、内部片段、最多 3 次；C | 模型基准与不同量化上下文边界 |
| T08 | 默认模型一键下载与删除 | qwen3:1.7b/Q4_K_M；同目录下载、后台 worker、Range 续传、取消/重试、完整 SHA 校验、删除；[当前验证](reports/alpha11.md) | 实体设备、官方 CDN 全文件与系统重启/长时断网矩阵 |

阶段出口状态：Phase 2 主要原型出口通过模拟器；Phase 3 真实 API 与原生 GGUF 通路已补验，完整阅读器/语义质量/硬件验收开放；Phase 4 合成大文件基础通过，完整性能门槛开放；Phase 5 基础文字批注/恢复通过，完整兼容/硬件矩阵开放。实体硬件测试目前为 0。
