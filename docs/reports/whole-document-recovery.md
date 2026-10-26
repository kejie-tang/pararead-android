# 全篇翻译进度与恢复

日期：2026-10-26。补齐全篇任务的原生持久游标、恢复、取消及页数统计。默认模型仍为固定 SHA 的 Ollama `qwen3:1.7b` Q4_K_M，思考关闭、CPU 线程设置保留；本次没有变更权重来源或推理后端。

## 行为

- 点击“全篇 / 继续”开始逐页任务。状态区显示已处理/总页数、成功页数、含失败句页数；“处理完成”不代表失败句也翻译成功。
- 翻译经校验写入 Room 后才发送 committed。整页请求结算后，job.progress 写入原生游标；JS 等待 job.saved 再提取下一页。未完成页不会推进游标。
- 重开同文档时，RUNNING 任务在阅读器准备好后恢复；同页已提交句子走缓存。恢复必须匹配 PDF 修订、总页数及翻译配置指纹。模型/语言/术语变化不继承旧游标，CPU 线程数/批次变化可复用。
- 主动取消会停止原生队列和后续 bridge 分块，保存 CANCELLED；重开保持停止，点击“全篇 / 继续”接续。ERROR 也需手动继续。已完成的任务再次点击全篇从首页扫描，已缓存句子不重复生成，先前失败句可重试。
- 当前页/选句已有请求时，全篇等待其结算；作业活动页和 pending 状态不在中途被视口淘汰。
- 每文档一个 JobRecord，保留最近一个配置。JSON v1 保存 jobId/revision/fingerprint/total/nextPage/failedPages/status，未修改 Room schema；旧版仅有 done/total 的记录不用于恢复。

## 已执行验证

| 范围 | 结果 | 证据 |
| --- | --- | --- |
| 构建、JVM、Lint | Debug APK/测试 APK 构建通过；JVM 24 项；Lint 0 错误、34 警告 | [构建](whole-job-build.txt)、[新游标测试 4 项](whole-job-tests.xml)、[元数据](verification.json) |
| JS/bridge | 13 项通过，其中新增 7 项：起始游标跳页、持久确认顺序、提取/推理时取消、已有页请求等待、保存/提取失败、81 句分块取消、原生确认失败 | [输出](whole-job-web-tests.txt) |
| Android 恢复及既有回归 | API36 ARM64 AVD，9 项通过；其中新增 2 项使用真实 WebView/Room/Mock 队列 | [instrumentation](whole-job-android-tests.txt) |
| 原阅读器契约 | 16 项通过 | [契约测试](../../test/source-contracts.test.mjs) |
| Chromium 触屏阅读器 | 双向定位、同高、完整当前页、竖屏工具栏边界、增量批注重解析通过；无 JS 错误 | [JSON](android-reader-browser.json)、[输出](whole-job-browser.txt) |
| APK | ARM64 JNI 保留、签名校验与 16KiB 对齐通过 | [大小/摘要](verification.json) |

Android 新测试在第 2 页译文已提交、但第 2 页游标尚未发送时挂起进度。此时 Room nextPage=2，重建 Activity/ReaderHost/WebView 后自动完成至 nextPage=15（14 页示例论文），jobId 保持一致。检查第 2 页已提交记录的完整内容及 created 时间均未被改写，验证部分页缓存复用。另一路主动取消后重建，游标与 CANCELLED 状态保持；手动继续生成新 jobId 后完成。再将旧 RUNNING 游标与新目标语言组合，确认不会自动恢复旧任务，手动启动生成新配置的任务。

## 边界

本次测试使用 **MockTranslationProvider**、Android 模拟器（1080×2400@420dpi、4GiB），不是实体天玑 9400 或真模型吞吐测试。测试覆盖 Activity/ReaderHost/WebView 重建；未执行进程强杀、重启、Doze、磁盘不足、真实模型持续全篇或 100/300/500MB 全篇遍历。先前的真实模型/大文件证据仍保持原范围。

当前无后台服务或系统唤醒任务；恢复发生在用户打开对应文档后。尚无缓存命中及句子级分类计数、失败页列表或跨配置的多任务历史。语义翻译质量与复杂论文仍需独立验收。

```sh
npm run check:reader
npm run test:android-web
scripts/gradle.sh :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e class org.pararead.android.WholeDocumentRecoveryTest,org.pararead.android.ModelManagerTest,org.pararead.android.ReaderIntegrationTest,org.pararead.android.CpuThreadsSettingsTest org.pararead.android.test/androidx.test.runner.AndroidJUnitRunner
```

测试会暂时使用 Mock/manual/独立术语指纹，并恢复设置与原有作业记录；测试译文/性能证据保留在模拟器数据库中，不用于真实模型质量或速度评价。
