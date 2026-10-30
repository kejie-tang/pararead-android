# 当前页自动翻译、单击选中与 Qwen thinking 核对

## 跳页停滞原因与修复

原先 web 客户端只区分 none/pending/done/error，不记录 pending 属于哪一次视口。当邻页已排队时跳到该页，Android 会取消旧视口的 speculative 工作；新视口检查到该句仍为 pending，跳过请求。旧任务取消确认稍后将句子还原为 none，但没有后续触发。因此可能一直空白，直到点击句子才通过 priority=0 再次请求。

修复后：

- 每个翻译请求记录视口 epoch 与实际优先级；翻页递增 epoch。
- 当前视口接管旧 epoch 的 priority 1/2 任务，当前页还可提升同 epoch 的邻页请求。
- 接管先更新 sentence owner；旧任务的取消、失败或译文回复不能清除/覆盖新 owner。
- 自动任务不会抢占显式点击（priority 0）或全篇任务（实际 priority 3）。
- 桥接层将 STALE_VIEWPORT 及跳页跳过的剩余批次作为取消处理，而不是句子翻译失败或伪装成功。
- 本地译文仍先恢复；同一视口不重复提交已 pending 的工作，已经 done 的句子不会重复翻译。

## 交互与范围

Android bootstrap 现在发送 `selectOnHover=false`，Web 默认值也为关闭。PDF 和译文面板都保留单击选中；鼠标/触笔悬停不选中。该行为独立于自动翻译。

新用户默认 `translateRange=page`。已有用户保存的 page/nearby/manual 选择不迁移：page/nearby 自动翻译当前页，manual 只在点击句子或翻译操作时请求缺失译文。设置页面增加中英文说明，避免把关闭 hover 与关闭自动翻译混为一谈。

## Qwen thinking 核对

本次没有修改模型提示词或改变既有翻译缓存指纹。原实现已经关闭 thinking：

- 本地：`LlamaCppProvider.generate` 调用 `ModelChatTemplate.render`。Qwen3 / Qwen3.5 的 assistant 前缀以一个**空且闭合**的 `<think>\n\n</think>\n\n` 结束，生成从 thinking 之后开始。出现空的 think 标签是为了预先结束 thinking，而非要求模型生成推理过程。
- API：`OpenAICompatibleProvider` 的实际 HTTP 请求体包含 `chat_template_kwargs.enable_thinking=false`。
- 译文校验会拒绝含 think 标签的输出，不将它视为正常已验证译文。

新增 provider 级断言，检查实际传给 `LlamaRuntime.generate` 的输入，覆盖 qwen3/qwen35、CPU/Vulkan、JSON/text 两种输出格式。API 回归改为解析实际请求 JSON 并断言布尔值 false。

这些是配置/提示词和模拟 runtime 回归，不是加载真实 GGUF 的性能或思考 token 实测。第三方 API 服务是否遵循 enable_thinking 仍取决于该服务实现；本次未连接任何远程模型端点。

## 回归证据

- 新增 `test/android-viewport.test.mjs` 九项测试。修改前稳定复现“邻页转当前页”和“快速返回旧 pending 页”两个失败用例，修改后通过。
- TypeScript、30 项 Android Web 测试、16 项未修改上游契约测试通过。
- 真实 PDF.js + Playwright 回归通过：关闭 hover 后 PDF/译文栏悬停均不选中，点击仍生效；假宿主延迟确认旧预取任务取消，跳到第 2 页和第 6 页后无需选中即可翻译；手动模式重开仍恢复缓存。
- 浏览器测试使用原创六页示例、明确标注的 Mock 译文与桌面 Edge/Chromium 触摸模拟，不是 Android 真机测试。测试进程保存独立结果，不覆盖历史截图/报告。

JVM 单元测试与本地 APK 构建结果见本次本地构建交付报告。没有真机/模拟器/instrumentation 测试；本次修改不自动推送或替换已公开的 alpha.12 Release。
