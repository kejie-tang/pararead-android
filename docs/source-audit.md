# Phase 1 源码审查

审查日期：2026-10-25（Asia/Shanghai）。基线为 [ParaRead c9d1034](https://github.com/Chuanyunux/pararead/tree/c9d103481e4e3fa065cee6a52e611b8be20866c6)，版本 0.6.1，提交日期 2026-09-30。以下是实际源码行为；Android 设计另见 architecture.md。

## 源码取得与完整性

Git HTTPS 克隆连接失败后，使用 GitHub API 取得提交和完整文件树，下载同一提交的 codeload 快照。逐文件以 `SHA1("blob " + length + NUL + bytes)` 对照 Git blob 哈希验证。493 个审查文件全部匹配，未修改上游源码。演示 GIF 不参与构建，明确排除。此校验固定内容，不代替对上游作者身份的独立信任判断。

## 1. 阅读器入口和 PDF.js 补丁

| 位置 | 实际行为 | 移植决定 |
| --- | --- | --- |
| `src/pdf-viewer-provider.ts:52` | 删除 viewer.html 原来的入口标签，再注入资源 URL、配置和 CSP；处理保存、备份、重载与外链 | 替换 VS Code 宿主；保留同一阅读器入口，生成 Android HTML |
| `src/webview/main.ts:49` | 一次取得 `acquireVsCodeApi()` 并共享给补丁后的 viewer；串联句子、面板、高亮、保存、同步和连接线 | 增加宿主适配层，不重写阅读器 |
| `patches/pdf.js.patch:1` | 去除原 HTML CSP；将 PDFLinkService 换成 VSCodeLinkService，指定范围内的链接经宿主打开 | Android HTML 必须重新提供 CSP；链接适配为原生路由 |
| `pdfjs_version.txt:1` | 固定 `6.2.108@7d9b2e0095e602ad274f05bb02fbb4125b30d93f` | 先保持此版本，升级另作兼容性回归 |
| `tools/check_pdfjs.mjs:1` | 检查 CSP、资源、语言文件、相对 worker 路径、链接保护和页面定位时序 | 保留上游检查；追加 Android 路径及离线资产检查 |

桌面 CSP 由宿主补回，不是完全没有 CSP。不能仅复制去掉 CSP 的 viewer.html。PDF.js viewer、worker、cmaps、字体、WASM、图标和 locale 必须作为一套资产打包。上游刻意使用相对 worker 路径以避免 VS Code 中的启动延迟；Android 同样先保留相对路径，再实测 WebView 行为。

## 2. 句子、坐标与阅读顺序

[HeuristicSegmenter](https://github.com/Chuanyunux/pararead/blob/c9d103481e4e3fa065cee6a52e611b8be20866c6/src/webview/segmenter/heuristic.ts#L74) 执行 text items → 行 → 块 → 句子。`protect.ts` 处理缩写、小数、版本号、URL、引文、CJK 标点、连字和跨行断词。输入来自 `getTextContent({includeMarkedContent:true, disableNormalization:true})`，过滤 marked-content 后的索引必须与 text layer span 一致。

程序生成句子 ID `p{page}-b{block}-s{index}`、块 ID、PDF user-space 矩形和 spanRange。`SentenceStore` 按页懒提取、按页复用 Promise，重载时 generation 递增，丢弃旧结果。所有已访问页、句子 ID 和分段 Promise 持续留在 Map，尚无淘汰或持久化。

结构角色为 `heading / paragraph / list / caption / note / figure / code`，标题 level 为 1–3。代码块不送翻译；连续图中文字在译文面板合成占位提示，图表标题仍按 caption 显示。保留这种区别，不能把图中文字占位误报成已翻译所有表格单元格。

**双栏支持有前提**：算法依赖 PDF 内容流已经接近阅读顺序，使用坐标判断换行、分块和跨栏段落续接；它不是任意坐标乱序 PDF 的完整版面重排器。上游 README 也列出跨页句子会拆开、独立公式可能被视为句子、作者列表可能被视为标题的限制。Android 要保留原能力，并为行优先乱序、跨栏浮动图和旋转页补充测试。

## 3. 同高对齐与双向跳转

- `geometry.ts` 统一 PDF ↔ viewport ↔ client 坐标。应复用 viewport 转换，不手写 Android dp 到 PDF 的比例。
- `highlight-overlay.ts` 绘制临时原文高亮，页重绘后重新定位；`scrollToSentence()` 用 PDF.js 页面目标定位。临时选句高亮不写入 PDF 批注。
- `SelectionController` 共享 `{sentence,pinned,source}`。PDF 单击 pin 后翻译所属段落；译文单击 pin 后跳转原句。原版局部操作是段落粒度，不是只请求那一句。
- `panel.ts:124` 处理译文点击与悬停，`:326` 按页创建结构化 DOM，`:373` 在块中保持逐句 span；不用模型生成 HTML。宽度、展开和显示原文状态经 `getState/setState` 保存。
- `scroll-sync.ts:104` 优先将选中句子的译文 top 对齐原句 top；未选择时使用阅读区高度 30% 位置，按块内行位置插值。手动滚动译文暂缓同步 1500ms。鼠标 X 用于区分双栏，触屏需要持久化最近选中栏。
- `connector.ts` 跨 PDF 和译文面板画 SVG 连接线，随滚动和重绘更新。两个面板必须共享 WebView DOM 坐标系。

面板会保留所有已分段页面的 DOM。仅给 PDF canvas 加缓存上限不足以解决整篇翻译后的内存增长。

## 4. 宿主消息与生命周期

[messages.ts](https://github.com/Chuanyunux/pararead/blob/c9d103481e4e3fa065cee6a52e611b8be20866c6/src/messages.ts) 提供 TypeScript 联合类型和基本运行时检查：`translate/translations/translationDone`、整篇翻译 job、settings、dirty、getData/data/saved、reload、chooseTargetLanguage。外链使用独立 `{open}`。请求 ID 和 job ID 是数字；没有文档 ID、协议版本、会话 ID、内容修订号或单请求取消消息。运行时验证也没有批量大小和字符串长度上限。

`main.ts` 检查 message origin。`TranslationBridge` 每 30ms 合并已完成句子的结果，**这不是模型 token 流式输出**。关闭 WebView 会 abort 请求；整篇 job 可以取消；`pagechanging` 只请求新范围，不取消旧页请求。`TranslationClient.reset()` 清理待完成请求，旧 requestId 的回复不会再显示。

## 5. 翻译协议与质量

`Translator` 负责缓存查询、相同文本去重、按段落和字符预算批次、并发信号量以及缺失句子重试。默认并发 2、每批最多 40 句、字符预算 3000。模型只需回显程序生成的批次局部 `s1...sN`，程序映射回页面句子 ID；没有依赖模型创造 ID。

`prompt.ts` 指令保留公式、代码、模型名和引用。`parse.ts` 接受数组、`translations` 数组、Markdown fence 和旧 `zh` 字段；忽略未知 ID，空译文视为缺失。缺失结果在批次结束后逐句重试一次，单句仍缺失则失败；网络客户端另有最多 3 次重试。成功句子入缓存。重要缺口：

1. 重复 ID 后一个覆盖前一个，不报告重复。
2. 公式、数字、单位、URL 等只有 prompt 要求，没有结果级不变量校验。
3. `client.ts:106` 明确 `stream:false`，尚无 SSE、finish_reason 截断校验或 token 流解析。
4. extraBody 在默认字段后展开，可覆盖 model/messages/stream 等字段。Android provider 需隔离允许配置的字段。
5. `providers.ts` 默认关闭 DeepSeek thinking；没有 Qwen3 GGUF/JNI 模板设置。
6. 缓存内容键去重是正确的，但共享 inflight Promise 的所有者取消可能影响仍需要结果的其他请求，Android 需要订阅者语义。

## 6. 缓存、设置与进度

`cache.ts` 使用 SHA-1，输入为 NFC + 折叠空白的原文、model、targetLanguage、promptVersion、glossaryHash，以 NUL 分隔。256 个分片，文件名为哈希前两位 `xx.json`，格式 `{contentKey:{"zh":"任意目标语言译文","t":毫秒时间戳}}`。500ms 延迟 flush，临时文件后 rename。`zh` 为兼容旧版而保留，不代表该缓存只支持中文。

缓存键**不含 sourceLanguage、实际模型文件哈希、模板、量化、后端修订和采样设置**。同名模型内容改变时可能复用旧译文。Android 新缓存采用版本化指纹，旧格式导入保留 provenance，不能静默视作新模型已验证结果。

`config.ts` 和 `languages.ts` 支持 auto 源/目标语言及 11 种语言（简繁中文分别有代码）；平铺术语表适用于所有语言，按语言嵌套条目优先覆盖。Android 直接复用这些规则；auto 目标改为跟随应用显示语言。

当前 panel 状态不是阅读位置。原版 PDF.js 自带 ViewHistory 使用 localStorage；没有 Android 文档索引、SAF 授权管理或 Room 阅读位置。Android 必须显式保存文档修订、页码、PDF 锚点、缩放、旋转和选句，不能只复制面板 getState。

## 7. 大 PDF 与批注的实质限制

桌面入口传 URL 给 `app.open(config)`，**不是在 TypeScript 入口主动 readFile 全文**。但这不能证明 PDF.js 内部内存有界：

- [`pdf.worker.mjs:8484`](https://github.com/Chuanyunux/pararead/blob/c9d103481e4e3fa065cee6a52e611b8be20866c6/assets/pdf.js/build/pdf.worker.mjs#L8484)：ChunkedStream 构造时分配 `new Uint8Array(length)`，按原 PDF 偏移写入；即使禁用预取也有整文件长度的 backing store。
- `pdf.worker.mjs:70691` 的 GetData 和 `:70812` 的 SaveDocument 都调用 `requestLoadedStream()`，后者还要构造保存输出。
- `save-bridge.ts:54` 在已有批注时调用 saveDocument，否则 getData；把完整 Uint8Array 回传宿主。单纯把这次桥接拆成小包不能消除上游全量序列化。
- `PDFDocument.isOwnWrite()` 对刚保存的文件再整文件读取算 SHA-1；Android 不能照搬。

因此第一版可沿用 PDF.js 行为，但不能宣称已严格消除整文件 JS 分配或已经通过 500MB 内存验收。详见 architecture.md 的 ADR-04/05。

## 8. 上游自动化与缺口

原版使用 Vitest，7 个 `.test.ts` 文件覆盖翻译/重试/缓存、宿主配置、语言、分词、多语言和服务预设。global-setup 从 PDF.js 自带双栏论文生成文本 fixture，并尝试下载 Attention 单栏论文；离线时后者会跳过。`tools/harness.mjs` 可运行原版 PDF.js、Mock 翻译与调试对象，**没有自动浏览器点击、同高误差、触控笔或大文件性能断言**。

CI 运行 frozen-lockfile 安装、typecheck、PDF.js 检查、lint、format、Vitest 和 VSIX 打包。上游 Node 版本为 24。Android 后续需要增加 WebView instrumentation、Compose UI、Room 迁移、JNI 取消和 ARM64 性能测试。实际执行结果与范围见 [报告](reports/phase-1.md)。
