# 本机 Ollama GGUF 与 llama.cpp 实测

日期：2026-10-26。复用本机 `qwen3:1.7b` 的 GGUF，未转换、重新下载或修改 Ollama 权重。已安装 Homebrew llama.cpp，并用项目自己的 Kotlin/JNI 实现建立独立的 CPU 检查工具。实体平板仍未测试。

## 模型与两种引擎

- 本机文件为 GGUF v3、`qwen3`、Q4_K_M，1,359,279,776 字节。SHA-256：`3d0b790534fe4b79525fc3692950408dca41171676ed7e21db57af5c65ef6ab6`。
- 已导出带 `.gguf` 扩展名的独立 APFS 克隆：`artifacts/models/Qwen3-1.7B-Q4_K_M.gguf`，可复制到 Android 后通过设置导入；不随 APK 或 Git 分发。
- Android 现在支持一键下载此固定 Ollama layer 的 GGUF，完整校验后自动启用，也支持删除；默认模型名称为 `qwen3:1.7b`，关闭思考。无需在平板安装 Ollama，见 [模型管理与验收](reports/model-management.md)。
- GGUF 标签为 `1.7B`，Ollama 元数据参数计数为 2,031,739,904；按文件哈希识别本次权重。来源是现有 Ollama blob，没有把它声明为某个官方 Hugging Face revision 的相同字节文件。详情见 [模型记录](reports/local-qwen3-model.json)。
- Homebrew `llama-server`：0.7.0 / build11669 / `620d2225c`，通过项目的 `OpenAICompatibleProvider` 发真实 HTTP/SSE 请求。
- 项目 JNI：固定 `2280f0fd163b03d4331d49e08460f66502e35854`。macOS 工具直接编译 Android 的 `llama_jni.cpp`，并复用 `LlamaCppProvider.kt`、校验器与队列，没有重写推理封装。关闭 Metal、BLAS、Accelerate，测试工具默认 4 线程，可用 `PARAREAD_CPU_THREADS` 指定；ctx4096/batch512/ubatch128。此路径用于检查一致性，不能据此推算平板速度。

## 复现本机测试

本机已装好 `llama-server`、JDK21 和 Android SDK 的 CMake 3.22.1。新环境需要这些依赖；模型路径可换成已有的 GGUF。

```sh
cd /Users/kjtang/Documents/app/pararead-android
export PARAREAD_GGUF="$PWD/artifacts/models/Qwen3-1.7B-Q4_K_M.gguf"
scripts/check_local_llama.sh jni
# 单独保存 6 线程结果，保留原 4 线程基线
PARAREAD_CPU_THREADS=6 PARAREAD_CHECK_REPORT=docs/reports/cpu-threads-host-six.json scripts/check_local_llama.sh jni
```

API 测试需要两个终端，均设置上述环境变量：

```sh
# 终端一：前台运行；Ctrl+C 停止
scripts/serve_local_llama.sh
# 终端二
scripts/check_local_llama.sh api
```

服务只监听 `127.0.0.1:18080`，别名 `qwen3:1.7b`，CPU4线程、ctx4096、单 slot、关闭 thinking。Android 模拟器配置 API 基址 `http://10.0.2.2:18080/v1`；真平板上的 `127.0.0.1` 指平板自身，不能用它连接这台电脑。Android 首选导入 GGUF 走离线 JNI。

Android 的“设置 → 离线 GGUF → CPU 推理线程数”默认 `0` 自动选择：识别到 8 个可用核心时取 6 线程，也可手动指定 1–8。自动策略参考 PocketPal 的 [getRecommendedThreadCount](https://github.com/a-ghorbani/pocketpal-ai/blob/main/src/utils/deviceCapabilities.ts)：超过 4 核取 80% 向下取整，否则使用全部。保存设置会重建会话，线程数同时用于生成与批量前填充，已有译文缓存不失效。该设置只控制应用内 JNI，不控制兼容 API 服务的线程；主机测试的环境变量也不控制 llama-server。新增 6 线程和 UI 验证见 [线程报告](reports/cpu-threads.md)。

## 已验证的范围

每条本机路径检查：单句中文生成、四句批次的 ID/保护片段、真实队列写缓存/重新创建队列后零推理调用、流式取消和随后继续生成。原始输出、延迟和尝试次数分别见 [JNI JSON](reports/real-model-jni.json)、[API JSON](reports/real-model-api.json)。`deltaEvents` 是解码后事件数，不是 tokens/s；`firstDeltaMs` 包括前填充，首次 JNI 调用还包含模型加载。

另外在 API36 ARM64 AVD（ranchu、6GiB内存、4 vCPU）内经正式 TranslationQueue 执行相同 Android JNI，检查两句批次、流式、取消及继续推理；最终复测开启飞行模式。见 [Android 原生结果](reports/real-model-android.json) 和 [instrumentation 输出](reports/real-model-android-test.txt)。该测试通过文件参数指定模型，**不代表 SAF 模型导入界面、WebView 真模型整页翻译或实体平板已验收**。

本次 Android 两句批次的首轮把另一句的代码占位符放进引用位置，校验拒绝；正式队列拆成两个单句修复，共3次 provider 调用后全部提交。首次合计12.09秒，首个 delta 为2.67秒（包括模型加载），取消等待18.4毫秒；取消后重复测试亦需3次调用、合计11.78秒。原始失败批次及随后两次修复输出均保留在最终 JSON 的 attempts 中，不能把最终通过解释为首轮批次成功率100%。

## 测试促成的修复与限制

1. 原 JSON 解析器接受有效对象之后的第二段 JSON。现在检查输入是否消费完毕，尾部多余内容拒绝入库；新增回归测试。
2. 小模型曾追加 glossary/Markdown 或复制输出模板。JNI 新增翻译对象 GBNF 约束，格式由采样器约束；数量、ID、非空与保护片段仍由 Kotlin 验证，重试仍有上限。
3. 原占位符在每句从 P0 重编号，现改为批次内唯一编号；拒绝把别句占位符放入当前句，并覆盖流式草稿恢复。prompt/validator 指纹已升级，旧配置缓存隔离。

初始问题记录保留于 `real-model-jni-initial.json`、`real-model-jni-strict.json`、`real-model-android-initial.json` 和 `real-model-android-raw-batch.json`；后者明确记录直接批次验证失败。最终 Android 报告验证的是正式队列拒绝错误并有限修复的行为。

**结构通过不等于学术语义质量通过。** 初始 Android 输出把引用 `[12]` 称作“代码”；样例也出现 URL 后重复标点或不自然措辞。程序保证保护片段对应，不保证模型对所有上下文的理解。尚无真实论文人工质量基准、长时间取消压力、不同量化对照、实体平板性能/温控或与100–500MB PDF并行的峰值内存数据。各耗时均为少量冒烟样本，宿主存在编译/模拟器等其他任务，不用于平台间性能比较。
