# Alpha.15：当前页自动翻译、单击选中及 thinking 配置验证

## 合并基线

基于 alpha.14 的 main 提交 `e0a1a237f8f64403befdbab5631685d07df39b62`，三方合并从 alpha.12 开发的修复。alpha.13/alpha.14 已公开发布，因此使用新的 versionName `0.2.0-alpha.15`、versionCode `15`，不覆盖旧标签或附件。

保留新版书库、继续阅读、重命名/删除、页码恢复和全屏滑动隐藏控制栏。ReaderHost 同时保留 readerFullscreen/showControlsLabel bootstrap 参数与 reading/pageCount 持久化；测试脚本同时保留原有 full-screen 回归和新增 viewport 回归。

## 修复

- pending 请求记录视口 epoch 和实际优先级。跳到已排队的邻页或快速返回旧页时，当前页接管旧 speculative 工作；旧取消回复不清除新 owner。
- 保留显式点击和全篇任务的 ownership，不重复提交同视口 pending / 已完成译文。
- STALE_VIEWPORT 与跳页跳过的后续批次属于取消，不伪装翻译成功或把句子永久标为失败。
- Android 和 Web 默认不悬停选中，只在点击时选中；自动翻译与选择动作独立。新用户默认 current page，已有 manual/nearby/page 选择保留。设置中有中英文说明。
- Qwen thinking 原配置未变：native qwen3/qwen35 预填空且闭合的 thinking 段，API 发送布尔 `enable_thinking=false`。新增 provider 输入断言，覆盖 CPU/Vulkan 与 JSON/text。未改变翻译缓存指纹。
- Windows 主机 CMake 支持 Clang，并正确保留 Ninja 路径的反斜杠。Unix 编译器优先顺序保留。

[竞争问题及 thinking 链路细节](reader-viewport-and-thinking.md)。

## 合并后的本地验证

环境：Windows 11 x64、JDK 21.0.13、Node.js 26.13.0、Python 3.12.15、Gradle 8.14.3、SDK 36、NDK 28.2.13676358、CMake 3.22.1、Vulkan SDK 1.4.363.0、LLVM-MinGW 20261020。上游固定源码和工具下载摘要已校验。

| 检查 | 结果 |
| --- | --- |
| TypeScript | 通过 |
| Android Web | 30/30 通过，含新增9项 viewport/bridge/default 回归 |
| 上游源契约 | 16/16 通过 |
| Core JVM | 52/52 通过 |
| Engine JVM | 9/9 通过，含实际 provider 到 fake runtime 的 thinking-off 输入断言 |
| ARM64 Debug APK + 测试 APK 编译 | 通过，完整原生引擎，不使用 skipNative |
| Lint Debug | 0错误、50警告 |
| apksigner / ZIP与ELF 16KiB对齐 / CPU和Vulkan插件 / 许可与无模型打包检查 | 通过 |

[本地检查汇总](alpha15-local-checks.json)。本地 debug APK 使用本机新调试证书，**不是公开发行附件**，不可作为同签名覆盖旧发布版的包。

## 浏览器回归

[合并后的真实 PDF.js 测试结果](alpha15-browser.json)，使用桌面 Edge/Chromium + Playwright 触摸模拟与原创六页示例，译文为显式标注的 Mock 输出。

- 关闭 hover 后 PDF 与译文栏悬停均不选中，点击仍联动。
- 假宿主延迟旧预取取消确认；跳到第2页及第6页无需选中即可自动翻译。
- 手动模式重开恢复缓存且不请求新翻译；选句对齐与增量批注保存正常。
- 保留并通过 alpha.13 的两侧滑动收起/恢复控制栏、窄屏恢复按钮、程序滚动忽略、选句/批注保护、退出恢复等场景。

日志仍有既有 harness 容许的一个 404 资源提示，其余检查通过；没有把旧截图或旧设备结果冒充本次结果。

## 发布与验证边界

公开 release 必须由 GitHub 签名工作流重新构建，下载并校验附件 SHA-256，并核对实际 APK 的原预览证书 SHA-256：

```text
e044c8f329f42465866add4d041f29c498009361241610c8ee2de43ae6e87a45
```

该证书仍为沿用的 Android Debug 预览身份，不是新生产密钥。匹配后才可公开为预览版并按同签名覆盖安装。不要为绕过安装错误卸载旧版，以免丢失文档、译文和模型。

**本次未运行 Android 真机、模拟器或 instrumentation 测试，也未加载真实 GGUF 或连接远程模型进行推理测试。** 测试 APK 编译不等于执行设备测试；fake runtime/本地 HTTP 断言证明配置和调用参数，不证明第三方 API 必然遵循 thinking 标志，亦不代表真实模型质量或性能。
