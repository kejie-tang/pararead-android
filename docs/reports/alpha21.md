# alpha.21：保留不完美译文与单次生成

版本：`0.2.0-alpha.21` / versionCode **21**。基线：alpha.20 的 `48c1f269f4656486a48d1a9122c88d2c31d00a61`。

本记录是**版本21完整本地重跑**（强制 `--rerun-tasks`，145 个任务实际执行，Core/App/Engine 测试均非 UP-TO-DATE），机器可读证明与六份生成资源哈希见 [alpha21-validation.json](alpha21-validation.json)。[translation-warnings.md](../translation-warnings.md) 中版本递增前的两轮实现阶段日志保持历史含义，不当作版本21 APK 身份证明。远端 Debug CI、签名 release APK 验证与完整匿名下载仍是公开发布前的独立必经门禁。

## 行为

- 任意错误已有可用译文时保留正文，并追加实际错误码 `*****（actual_error_code）`，不限于 `protected_token_mismatch`。包括流/非流 API 截断、普通超时、结构/格式异常等；无正文才只显示失败提示。
- PDF/TXT 均使用纯 `textContent`、正常译文字体及颜色，浅深色无红色或下划线。多个诊断合并去重。
- 普通错误不再触发队列修复、逐句重译、纯文本修复或 HTTP 隐式重发；GPU 仅在明确兼容异常下安全清空草稿后回退 CPU 一次。
- 可安全归属的标注结果先保存再提交，重开直接复用；长句保留已有分片。存储失败保留正文供显示，但不假确认持久化成功，仍可由用户主动重试。
- 草稿和失败结果共享有界安全解析。完整扫描有界响应来核对身份，不能截掉后缀后假定 ID 唯一；部分行没有正文时也统计其 ID，键和值按 JSON 解码，包括转义形式。拒绝重复/外来 ID、嵌套伪正文及推理文本。后续身份冲突会清空早先草稿，取消不缓存，Restart 不复用旧 GPU 内容。
- 不改变数据库实体、有效缓存内容键、模型、提示词、生成语法与全局配置指纹；保留 PDF 渲染/批注/导出、TXT 稳定页与句子 ID、Read only、后台断点和保存/drain 屏障。

## 版本21实际验证

| 门禁 | 结果 |
|---|---|
| Core JVM | 111 项：110 通过、1 项可选真实模型测试跳过 |
| App JVM | 18 通过 |
| Engine JVM | 11 通过；假运行时，不是真实推理 |
| Web | 149 Android 合约 + 16 独立上游快照基线 = 165 通过 |
| 导航与 TypeScript | 独立导航 2 通过；完整类型检查通过 |
| 本地 APK | ARM64 native-enabled Debug APK、AndroidTest APK 编译通过；aapt 确认 versionCode21 / alpha.21 |
| Lint | 0 errors / 54 warnings |
| 分发检查 | 本地 debug 签名、ZIP/ELF 16KiB、ARM64 native libraries、唯一原创示例、许可/通知和无 GGUF 通过 |
| 实际桌面浏览器 | Edge；生成的 PDF/TXT、只阅读 PDF、后台 PDF 门禁全部通过 |

浏览器包含 `TRUNCATED_STREAM_length`、`HTTP_500`、`HOST_TIMEOUT` 三种失败的浅/深色正文保留与普通样式，及标注缓存恢复、PDF 几何/批注保存、TXT 触摸/双栏/章节/进度/保存冻结等既有回归。Native、存储、译文及故障输入为模拟，不是 Android WebView 或真实模型行为证明。上游快照测试中的重试行为仅为历史基线，不是生产 Android 翻译队列。

Native 日志：`C:/Users/57452/AppData/Local/Temp/pi-background-terminal/bg_843f13e07ae7c0b8.log`。Web/浏览器日志：`C:/Users/57452/AppData/Local/Temp/pi-background-terminal/bg_bf58ea6ba94bac0d.log`。本地原始浏览器报告与截图位于 `output/alpha21-browser/`，不覆盖历史发布报告。独立实现审查的所有 P1/P2 及转义 ID 补充缺口已修复并复审关闭；发布范围独立审查已通过（No issues found / OK），无开放 P0–P2；随后按已审查的 29 个文本路径冻结发布。

## 尚未执行的验证与发布要求

**本版没有 Android 真机、模拟器、仪器化执行或真实模型推理，也没有下载模型。** 编译 AndroidTest APK 不等于执行测试。设备上的实际阅读、系统返回手势、安装升级和模型行为仍需设备验收。

公开发布前必须对冻结的同一源码提交完成远端 Debug CI 和签名 workflow，校验 `org.pararead.android`、versionCode21、仅 ARM64、APK v2 单签名、ZIP/ELF16KiB、六份冻结阅读器资源、原示例/许可/通知及无模型/密钥，随后逐个完整匿名下载并重新计算 APK、notices、SHA256SUMS 三个附件的哈希；不能以草稿存在或 HEAD 成功替代完整下载。

沿用既有公开预览证书 SHA-256：

```text
e044c8f329f42465866add4d041f29c498009361241610c8ee2de43ae6e87a45
```

本机/CI debug 包不是公开覆盖升级替代品。保留 alpha.20 和所有更早 tag、Release、附件与验证记录，不覆盖。相同证书的新 versionCode 支持保留数据的覆盖安装；不要为规避签名错误卸载或清除数据。
