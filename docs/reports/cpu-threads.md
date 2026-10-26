# CPU 推理线程设置

日期：2026-10-26。已实现可配置的 CPU 线程数，APK 仍使用 llama.cpp CPU 后端。实体天玑 9400 未连接，未测试其速度、温度或最佳线程数。

## 实现

- “设置 → 离线 GGUF → CPU 推理线程数”：`0` 为自动，支持手动输入 `1–当前可用核心数`，可恢复自动设置。非法数值拒绝保存。
- 自动策略参考 PocketPal 的 [getRecommendedThreadCount](https://github.com/a-ghorbani/pocketpal-ai/blob/main/src/utils/deviceCapabilities.ts)：4 核及以下用全部，超过 4 核取 80% 向下取整，所以 8 核时为 6。没有复制其代码；本项目使用 JVM `availableProcessors()` 获取进程可用核心数。
- 偏好保存在 SharedPreferences，进程重启后保留。保存关闭旧会话，新 provider 在下次翻译时以新线程数加载模型，同时设置 JNI 的 `n_threads` 和 `n_threads_batch`。不修改译文配置指纹，已缓存句子不重复翻译。
- 运行时可用核心数变化时，对手动值限幅；没有绑定核心亲和性。线程数越多不保证越快，也不代表使用了指定数量的大核。兼容 API 的线程由服务器控制。

## 实际验证

| 检查 | 结果 | 证据 |
| --- | --- | --- |
| Debug APK、测试 APK、core 测试、Lint | 构建通过；core 16 项；Lint 0 错误、33 条警告 | [构建输出](cpu-threads-build.txt)、[策略 3 项 XML](cpu-threads-tests.xml)、[翻译 13 项 XML](core-tests.xml) |
| Android 设置持久化、缓存指纹、阅读器回归 | ARM64 API36 AVD，3 项通过 | [instrumentation](cpu-threads-android-regression.txt) |
| 设置界面输入与重启 | 显示自动 6、范围 1–8；输入 9 拒绝；手动 6 保存并重启后保留 | [UI 结果](cpu-threads-ui.json)、[截图](cpu-threads-settings.png) |
| macOS 共用 CPU JNI，手动 6 线程 | 真实 GGUF 单句、4 句批次、队列/缓存重开、取消/再次生成，4 项通过 | [原始 JSON](cpu-threads-host-six.json)、[输出](cpu-threads-host-six.txt) |
| Android CPU JNI，自动 6 线程 | 8 vCPU/6GiB ARM64 AVD，飞行模式；正式队列双句、流式、取消/再次生成通过 | [原始 JSON](cpu-threads-android-real.json)、[instrumentation](cpu-threads-android-real.txt) |

Android 首轮批次混用了另一句的保护占位符，被拒绝；正式队列经两个单句修复后提交，共 3 次 provider 调用。首次合计 9.79 秒（含模型加载），取消等待 2.63 毫秒，再次执行合计 9.31 秒。原始尝试全部保留，不能据此宣称首轮批次成功或学术语义质量通过。

使用本机 Ollama `qwen3:1.7b` Q4_K_M 原权重，SHA-256 为 `3d0b790534fe4b79525fc3692950408dca41171676ed7e21db57af5c65ef6ab6`。这是功能冒烟测试，旧 4 线程报告保留；模拟器 vCPU 数和主机负载不同，没有将两次耗时当作线程加速对照。SAF 模型导入界面、WebView 真模型整页联动及实体平板验收仍开放。

复现主机：`PARAREAD_CPU_THREADS=6 PARAREAD_CHECK_REPORT=docs/reports/cpu-threads-host-six.json scripts/check_local_llama.sh jni`。Android 权重检查可传 instrumentation 参数 `ggufPath` 与 `cpuThreads`；后者省略或为 `0` 时自动选择。未提供 GGUF 时该 opt-in 权重测试跳过，不能计为真实模型通过。
