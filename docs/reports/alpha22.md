# alpha.22：原文直译与兼容性优先

- 版本：0.2.0-alpha.22 / versionCode 22；基线 2d4e23fc6f9ef6353ed2944a373378f7119c3513。
- 范围：35 个文本路径；新版本独立发布，不覆盖 alpha.21 或更早 tag/release/附件/报告。
- 原文不注入或还原 __PR 占位符；移除语言、原文复制、数字与引用的质量判断及相关训令。不承诺译文准确性改善。
- 新写入 prompt=7 / validator=7，优先新键；同配置6/6只读兼容，不清库、不回写旧记录、不猜补旧标记。旧全篇游标仍受精确指纹约束。
- 运行错误保留可安全归属正文，用普通括号代码，不显示占位星号；无红色、下划线或强调。必要 JSON/ID、推理隔离、取消/排空、兼容回退与保存确认保留。

## 本版实际完整重跑

- Native：--rerun-tasks，145 actionable tasks / 145 executed，最多2 workers。
- Core：126通过、1项可选真实模型测试跳过（共127）；App18通过；Engine11假运行时通过。
- Lint：0错误/54警告；ARM64 Debug和AndroidTest APK编译、本机debug签名、ZIP/ELF16KiB、通知原文/唯一原创示例/不含模型分发检查通过。
- Web：150 Android Web +16历史上游契约（总166），独立导航2，完整TypeScript通过。
- 实际生成资源的桌面Edge：正常PDF、TXT、Read only PDF、后台PDF均通过；模拟截断/HTTP/超时保留正文，浅深色普通样式；安全保存、PDF批注几何、TXT导航及后台确认屏障继续通过。
- 机器证明：[alpha22-validation.json](alpha22-validation.json)，绑定本版aapt身份、实际重跑日志和六份生成资源SHA256。
- 原始完整日志：C:/Users/57452/AppData/Local/Temp/pi-background-terminal/bg_b4863d83c3a1b0c7.log

## 独立审查

- 实现静态复核：sa-3c59fe61，OK；旧残缺标记与JSON字面think标签两项P2已修复关闭。
- 发布审查：No issues found / OK；ID：sa-1d259664；开放P0–P2：0。
- 新增/调整的可选模型与仪器化测试只更新兼容契约，不代表执行过设备/模型。

## 不夸大验证范围

本版没有Android真机/模拟器/instrumentation执行，没有真实模型推理或模型下载。浏览器的Native、存储、译文与故障均为模拟输入，AndroidTest仅编译。模板/native grammar与数据库实体不变，但prompt/validator版本明确变为7/7，不宣称缓存指纹未变。

公开分发必须再完成精确源码远端CI、保留证书签名、APK内容与六资源哈希验证，并完整匿名下载APK、notices、SHA256SUMS三个资产重新计算哈希。本地debug包不是公开升级包。

证书要求：e044c8f329f42465866add4d041f29c498009361241610c8ee2de43ae6e87a45。不要卸载旧版来绕过签名错误。
