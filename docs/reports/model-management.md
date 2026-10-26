# 默认模型下载、删除与关闭思考

日期：2026-10-26。已实现书库与“设置 → 离线 GGUF”的一键下载、进度、取消、重试和删除；默认 `qwen3:1.7b`，Q4_K_M、1.36 GB，关闭思考。APK 不内置权重，翻译使用应用内 CPU JNI，无需安装 Ollama。

## 实现行为

- 模型来源为 [Ollama 官方 qwen3:1.7b](https://ollama.com/library/qwen3:1.7b) 的 registry GGUF layer，固定 1,359,279,776 字节与 SHA-256 `3d0b790534fe4b79525fc3692950408dca41171676ed7e21db57af5c65ef6ab6`；不在运行时跟随可变标签。元数据和 CDN 分段核验见 [来源记录](default-model-source.json)。
- Android DownloadManager 执行后台下载，保存任务 ID 和独立 UUID 暂存文件名；Activity 关闭或进程重建后查询任务。网络暂停显示等待并可取消，失败支持重试。系统负责下载重试，应用恢复时继续完成后的校验安装；不承诺所有厂商后台限制或断网情形已经验收。
- 通过系统文件句柄，以 256KiB 缓冲复制至应用私有目录，验证 GGUF magic、完整大小、SHA-256，fsync 后原子发布；成功才切换模型并清理旧副本。下载校验峰值约需两份权重加 64MiB（约 2.8 GB），完成后保留约 1.36 GB。
- 手动导入共用安装路径。取消/失败清理临时文件、保留已激活模型。删除清除本机模型与模型设置；保留 PDF、Room 译文、阅读进度与批注，不删除用户选取的原始 GGUF。
- 使用 `/no_think` 及空 thinking assistant 前缀，与 Qwen3 的 `enable_thinking=false` 格式一致；API 请求继续设置 `chat_template_kwargs.enable_thinking=false`。没有开启思考的界面选项。
- 本地模型默认名称 `qwen3:1.7b` 与旧名称 `Qwen3-1.7B` 在缓存指纹中兼容，实际 GGUF SHA 继续隔离不同权重；API 模型名称保持区分。删除后重新下载相同权重可复用原译文缓存。

## 验证证据

| 范围 | 实际结果 | 记录 |
| --- | --- | --- |
| 构建与静态检查 | Debug APK、测试 APK 构建通过；Lint 0 错误，34 条警告 | [构建输出](model-download-build.txt)、[统一元数据](verification.json) |
| JVM core | 20 项通过；新增 4 项覆盖文件校验、取消、删除范围、默认关闭思考与旧缓存名称兼容 | [模型测试 XML](model-files-tests.xml)、[翻译 XML](core-tests.xml)、[线程 XML](cpu-threads-tests.xml) |
| Android 系统下载与阅读器 | API36 ARM64 AVD，7 项通过；含取消后立即重试、监控器重建、已发布模型与移除的旧任务协调、损坏摘要保留旧模型、导入与删除 | [instrumentation](model-download-android-tests.txt) |
| 完整真实 GGUF 下载安装 | 相同摘要的 1.36 GB 本机权重，通过 HTTP fixture → Android DownloadManager → 私有文件校验 → 自动启用，1 项通过 | [原始 JSON](model-download-full.json)、[instrumentation](model-download-full-test.txt) |
| 下载后离线推理 | 飞行模式、6 线程；真实中文、流式、取消后继续通过，输出无 thinking 文本 | [原始 JSON](model-download-offline-inference.json)、[instrumentation](model-download-offline-inference.txt) |
| 平板界面删除 | 2560×1600@240dpi；观察默认就绪与关闭思考，点击删除后模型目录空、设置清除、下载按钮可用；Room 6 张业务表计数一致，包含 394 条译文 | [UI 结果](model-management-ui.json)、[就绪截图](model-ready-tablet.png)、[删除后截图](model-deleted-tablet.png) |

下载进度入口的截图见 [书库下载进度](model-download-progress.png)。取消立即重试曾暴露系统延迟删除同名暂存文件的竞态，现每次使用 UUID 文件名并通过系统文件句柄读取；新增回归已通过。已发布模型遇到旧系统任务被移除时，会协调为就绪状态并清理该过期错误。

## 测试边界与复现

实际生产 URL 已由界面点击触发下载，观察到 33MiB 后暂停；主机另外完成 16MiB Range 请求，与本机已知权重对应字节一致。当前外网较慢，**未完成官方 CDN 到模拟器的整个 1.36 GB 下载**。完整文件测试使用 `adb reverse` 的本机 HTTP fixture，与默认权重大小/摘要完全一致；JSON 明确记录 `fullProductionCdnDownload=false`。fixture 耗时只用于确认功能，不作为外网速度或天玑 9400 性能数据。

实体天玑 9400、系统重启/Doze/低电量/存储不足等完整设备矩阵、SAF 文件选择器操作及 WebView 真模型整页翻译仍未验收。既有小模型批次混用保护占位符的问题仍由正式队列拒绝并有限修复；本次离线报告保留每次原始尝试，结构通过不等于人工语义质量通过。

```sh
scripts/gradle.sh :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
# 安装主 APK 与测试 APK 后
adb shell am instrument -w -e class org.pararead.android.ModelManagerTest,org.pararead.android.ReaderIntegrationTest,org.pararead.android.CpuThreadsSettingsTest org.pararead.android.test/androidx.test.runner.AndroidJUnitRunner
# 可选：在本机已有权重目录提供仅监听本机的 fixture，并设置 adb reverse
python3 -m http.server 18944 --bind 127.0.0.1 --directory artifacts/models
adb reverse tcp:18944 tcp:18944
adb shell am instrument -w -e class org.pararead.android.RealModelDownloadTest -e downloadFixtureUrl http://127.0.0.1:18944/Qwen3-1.7B-Q4_K_M.gguf org.pararead.android.test/androidx.test.runner.AndroidJUnitRunner
```

完整文件 opt-in 测试会先删除应用已安装的模型、重新下载并激活该模型，留给后续推理/删除检查；PDF 和译文缓存保留。不提供 URL 时跳过，此跳过不算真实权重通过。仅 debug 测试允许 localhost HTTP，生产目录固定 HTTPS。完整网络下载需要网络，安装后的 JNI 翻译离线运行。
