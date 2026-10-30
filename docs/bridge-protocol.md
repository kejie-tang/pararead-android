# JS ↔ Kotlin 消息协议 v1

本文件描述已实现协议。Phase 1 草案中的订阅去重等能力尚未全部落地；以本文件和运行时代码为准。

```json
{"version":1,"type":"translation.request","sessionId":"uuid","documentId":"sha256","documentRevision":"sha256:...","requestId":"translate-42","viewportEpoch":8,"configRevision":1,"payload":{"priority":0,"sentences":[{"id":"p12-b3-s2","group":"p12-b3","text":"A sentence."}]}}
```

| 方向 | 消息 | 实际作用 |
| --- | --- | --- |
| JS → Kotlin | reader.ready | bootstrap 握手；其余业务消息必须匹配新会话 |
| Kotlin → JS | session.open | session/修订、文件长度、range token、语言/范围设置、阅读状态；不含模型路径/key |
| JS → Kotlin | session.opened / session.error | 页数/打开完成或可见错误 |
| JS → Kotlin | page.visible | 递增 epoch；取消过期当前/邻页任务 |
| JS → Kotlin | page.cacheGet | 按页请求已持久分段 |
| Kotlin → JS | page.cached | 分段对象或 null；超 60KB 暂回退重新提取 |
| JS → Kotlin | page.textChunk | 每页 JSON 分块，收齐后写 Room |
| JS → Kotlin | translation.cacheGet | 与 translation.request 相同的有界原句列表；按原文及当前配置指纹只读 Room，不进入推理队列、不调用 provider；逐句返回 translation.committed，最后 translation.finished，未命中不报错也不翻译 |
| JS → Kotlin | translation.request | 1–40 句、原句 ID/group/text、priority；分段程序分配 ID |
| Kotlin → JS | translation.delta | 非持久草稿，sequence 单调递增；仅当前请求可显示 |
| Kotlin → JS | translation.committed | 精确校验并 DB commit 后逐句 `{id,translation,contentKey}` |
| Kotlin → JS | translation.finished | failures 与 cancelled；所有 pending 结算 |
| JS → Kotlin | request.cancel | 对应请求取消；翻译通过 finished(cancelled=true) 确认退出，没有独立 request.cancelled 消息 |
| Kotlin → JS | reader.command | translateCurrentPage/translateAll/cancelTranslateAll；translateAll 带原生 jobId 和 startPage 恢复游标；readerFullscreen 带 enabled 和 showControlsLabel，同步原生全屏状态及唤回按钮名称 |
| JS → Kotlin | job.progress / job.finished | jobId、total；progress 带 done 和 failed（当前页是否有未完成句）；finished 带 COMPLETED/CANCELLED/ERROR |
| Kotlin → JS | job.saved | 原生确认 JobRecord 已持久保存并返回 status；JS 等待确认才处理下一页，保存失败停止任务 |
| JS → Kotlin | reading.save | 合并页/位置/缩放/旋转/选句/栏状态；reading.saved 确认 |
| JS → Kotlin | annotation.changed | debounce 自动生成持久 suffix 草稿 |
| 双向 | document.save | host 发起保存，JS 回复增量长度；无长度的 JS 请求打开 SAF 另存 |
| 双向 | save.started / save.chunk / save.progress / save.end / save.finished | saveId、严格 offset、24KiB 增量块；持久化确认后清 dirty |
| JS → Kotlin | settings.chooseLanguage / external.open | 原生设置或点击外链 |
| JS → Kotlin | telemetry.event | 本地数值指标，不上传内容 |
| Kotlin → JS | protocol.error | 已解析请求的有界错误摘要；完全不可解析的 envelope 只拒绝并记录日志 |

每 envelope ≤65536 UTF-8 字节；ID 1–128 字符；epoch/revision 非负。原句 ID 必须匹配 `pN-bN-sN`、不得重复，每句 ≤16KiB、每请求原文 ≤32KiB、≤40 句。JS 会拆分一页的大请求。翻译请求等待最多 480 秒，只读译文恢复与普通消息 15 秒，页缓存 3 秒；超时取消，失败不假装已提交。

page.textChunk 每块 ≤5000 UTF-16 code units，≤64 块，最多并行收集 8 页；超过总页预算显式记 PAGE_TEXT_TOO_LARGE，不截断坐标。suffix ≤16MiB，每块解码后 ≤24576 字节，顺序必须连续。

listener 限定 `https://appassets.androidplatform.net` 与 main frame。Kotlin 核对 session/document/revision/config，WebView 配置不满足时显示更新提示。JS 对 translation sequence、terminal 去重并拒绝旧会话；配置更新重建 session。取消实际传播到 HTTP Call/JNI abort；JNI 在执行线程退出后结束 Flow，未实现模型加载卡住时的独立 watchdog。

PDF 二进制不走 JSON。范围端点 `/documents/<opaque-token>/ranges?begin=X&end=Y` 为同源拦截请求，返回 **200 + 当前分块长度**；每次 ≤256KiB、Long 边界检查，最多 4 个在途 fetch。PDF.js 合并范围最多 16MiB，错误关闭 transport 并显示 session.error。原文件没有全文 arrayBuffer/base64/getData 路径；保存消息中的 base64 仅用于新增批注尾部。

全篇 jobId 是 JS 安全整数范围内的原生 Long。宿主只允许当前 RUNNING 作业的 priority=3 翻译请求，核对 jobId 与当前游标页。job.progress 的 done 必须等于 nextPage（同一已确认页重发为幂等），推进后保存 nextPage=done+1；COMPLETED 必须已处理总页数。旧作业消息不改写新作业。恢复时核对文档 revision、页数和翻译配置指纹；用户取消不自动恢复。状态计数为页数，非模型成功率或缓存命中数。详情见 [全篇恢复报告](reports/whole-document-recovery.md)。

页面分段就绪后先执行只读译文恢复，包含 manual 模式；重新打开、翻页及内存淘汰后返回页面都会恢复。自动范围只为未命中句子发起翻译；manual 模式的未命中句子保持未翻译。恢复按页去重，reset/淘汰后的迟到响应不回填旧状态，配置变化重建会话并使用新指纹。译文仍存于既有 TranslationRecord，无数据库迁移。

兼容适配：原 translate/translations/translationDone 保持 UI 形状；getState/setState 改为宿主 reading.panelState；翻译/保存/作业游标由 Kotlin 权威确认。未实现独立 translation.started、settings.applied 或共享订阅计数，不应根据 Phase 1 草案推断这些能力存在。

全屏状态也随 session.open.settings 的 readerFullscreen 和 showControlsLabel 初始化。宿主切换全屏或菜单语言时通过 reader.command 更新，不重建 WebView 或翻译会话。工具栏收起只跟随 PDF/译文区域的触摸、滚轮和阅读键输入，不订阅同步滚动事件；批注工具、选句、原生文本选择、编辑控件和打开的菜单阻止收起。收起状态不持久保存，重新进入先显示菜单。
