# alpha.12 本地 Android 验证

验证日期：2026-10-30。应用源码为 `69f9e3a3b557b224ac440367f91352d2e8193abe`（`v0.2.0-alpha.12`）；后续只修改 Android 测试、CI 检查和文档，未修改该 APK 的运行时代码。

结论：在本地 ARM64 Android 模拟器和真实 PDF.js 浏览器测试中，未发现阻塞译文恢复或全屏阅读的问题。GitHub 构建的 release APK 已通过签名校验及保留数据的覆盖安装。此次不验证天玑 9400 真机的推理性能或翻译质量。

## 检查结果

[结构化统计和全屏尺寸](alpha12-local-checks.json)。

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| Kotlin core | 52 项通过，0 失败/跳过 | 本地 Gradle 测试结果；[构建日志](alpha12-build-checks.txt) |
| llama engine JVM | 8 项通过，0 失败/跳过 | 同上 |
| 应用和 Android 测试 APK | 编译通过 | [最终测试 APK 构建](alpha12-test-build.txt) |
| Android Lint | 0 错误、45 警告 | `:app:lintDebug`；不是零警告构建 |
| Android 平板布局 | 9 项通过，31.488 秒 | [instrumentation 输出](alpha12-android-tests-tablet.txt) |
| Android 手机布局 | 重复运行其中 3 项阅读器回归，全部通过，16.141 秒 | [instrumentation 输出](alpha12-android-tests-phone.txt) |
| 上游源码契约 | 16 项通过 | [Web 测试日志](alpha12-web-tests.txt) |
| TypeScript / Android Web | 类型检查通过，21 项 Web 测试通过 | 同上 |
| 实际 PDF.js 浏览器回归 | 通过；错误列表为空 | [结构化结果](alpha12-local-browser.json) |

本机使用 macOS、Node.js 24.15.0、Zulu JDK 21.0.13、Android SDK 36。Android 环境是 API 36 ARM64 模拟器、WebView 133.0.6943.137；平板窗口为 1920×1200 / 240dpi，手机窗口为 1080×2400 / 420dpi。平板与手机测试共享同一模拟器的数据，合计为 9 个独立用例、12 次执行。

执行命令：

```sh
npm test
npm run check:reader
npm run test:android-web
npm run test:reader
scripts/gradle.sh :core:test :engine:llama:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

Android instrumentation 执行 `ReaderRecoveryUiTest`、`WholeDocumentRecoveryTest`、`ReaderIntegrationTest`、`InterfaceLanguageTest`；手机窗口重复执行 `ReaderRecoveryUiTest`。

## 两项修复的实际行为

**已保存译文恢复：**手动模式下，Room 已保存的正文译文在 Activity 重建后自动显示；未命中句子保持空闲，没有新增 `translation_ms` 推理记录。另一个用例实际点击“本页”完成 Mock 翻译，返回书库并点击原文档重新进入，所有句子的译文与退出前一致；再次请求本页也没有新增翻译队列记录。浏览器回归独立验证了重开后可见译文及零新增 `translation.request`。

**全屏阅读：**平板布局的 WebView 从 1788×982 增至 1920×1132 像素；手机布局从 1080×1668 增至 1080×2264 像素。测试同时检查系统栏隐藏、Chromium 视口跟随原生布局改变、WebView 对象与 JS 标记保持不变、退出按钮、Android 返回键及 Activity 重建后全屏偏好恢复。设备安全区仍可能占用少量空间。

另外，整篇任务恢复/取消与配置失效、Room 缓存、PDF 区间读取、批注保存与重开、语言切换和 JNI 库加载的回归均通过。翻译恢复用例使用 Mock 或预置缓存，不能据此推断 Qwen 等模型的速度和翻译质量。

## 检查时修复的测试问题

- 移除不存在的 `androidx.compose.ui.test.assertDoesNotExist` 顶层导入；该断言是测试节点的成员方法。原应用 APK 可构建，但 Android 测试 APK 无法编译。
- 等待 JS 条件时推进 Compose 测试时钟，允许异步文档选择真正更新界面；启动测试 Activity 时清理旧任务，隔离入口状态。
- 译文可见性夹具改用正文句子。原夹具选中的是示例首页的图示标签，按阅读器设计显示为占位符，并不存在对应的正文 DOM span。
- 增加真实“翻译 → 返回书库 → 重开”回归，以及全屏实际尺寸和系统栏断言。
- Android CI 增加 `:app:assembleDebugAndroidTest`，以后会检查测试 APK 能否编译；CI 仍未运行模拟器 instrumentation。

这些修改只涉及测试和构建检查，不要求重打 alpha.12 的应用 APK。

## GitHub 实际附件与升级

[签名构建](https://github.com/kejie-tang/pararead-android/actions/runs/41018040195)与 [Android CI](https://github.com/kejie-tang/pararead-android/actions/runs/40889926313)均通过。下载 [alpha.12 Release](https://github.com/kejie-tang/pararead-android/releases/tag/v0.2.0-alpha.12) 的实际附件后重新校验：

| 附件 | 大小（字节） | SHA-256 |
| --- | ---: | --- |
| `ParaRead-0.2.0-alpha.12-android-arm64-release.apk` | 65,332,683 | `ad7036461cc0987a7ed254d22f35acac0c5dd87e927fa03219b0e256e3a80aa5` |
| `ParaRead-0.2.0-alpha.12-android-arm64-release-notices.zip` | 166,333 | `fb528825500489224ed80fdee38393963387bd96872b7513dc41e80de5524ecd` |

两者匹配发布的 `SHA256SUMS.txt`。APK 的 `apksigner verify` 和 `zipalign -c -P 16 4` 通过。[公开证书校验结果](alpha12-ci-apk-signature.txt)确认其 SHA-256 为 `e044c8f329f42465866add4d041f29c498009361241610c8ee2de43ae6e87a45`，与此前 alpha.11 Release APK 使用的持久预览证书一致。它是 Android Debug 身份的预览证书；release 构建类型本身并不改变签名身份。

实际覆盖安装没有卸载旧应用：旧示例文档和原有 1,359,279,776 字节的 Qwen3-1.7B 模型均保留。Android 功能测试使用相同应用源码的本地 debug variant，随后重新安装 GitHub release APK，另外通过普通界面进行阅读器冒烟验证。

GitHub release APK 的普通界面冒烟检查也通过：选择 Mock、翻译本页、进入与退出全屏、回书库再打开原文档，正文仍显示保存的 Mock 译文。见 [结果](alpha12-ci-release-smoke.json)与 [全屏截图](alpha12-ci-release-fullscreen.png)。这是另外的 UI 检查；“没有新增翻译请求”的计数断言来自上面的 instrumentation 和浏览器用例。Android 首次进入沉浸模式显示的系统说明需要点“知道了”，确认后退出按钮正常可用。检查结束后已恢复原来的 Offline GGUF 后端设置。

截至验证时 alpha.12 已公开为 pre-release。此次记录不包含实体平板、不同 WebView/Android 版本、真实进程强杀或长时间模型推理测试。
