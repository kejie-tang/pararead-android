# ParaRead Android 0.2.0-alpha.21 — 保留译文与单次翻译 / Keep translations & single-pass inference

## 中文

- **任何错误已有可用译文都尽量保留。** 不只 `protected_token_mismatch`：截断、超时、格式错误等也显示已收到且能可靠归属的正文，末尾追加实际错误码，如 `译文…… *****（truncated_stream_length）`、`*****（http_500）` 或 `*****（host_timeout）`。多个原因去重合并。
- **标注就是普通文字。** PDF/TXT 浅色与深色均与附近译文同色、同字体；不加红色、下划线、粗体或其他强调，星号不作为 Markdown/HTML 解释。
- **普通错误只生成一次。** 移除批次失败后的逐句修复与纯文本修复循环，关闭 API 的连接故障/408/503 隐式重发。仅明确的 Vulkan 后端兼容问题可安全清除草稿后回退 CPU 一次；普通截断、输出超限、坏模型及未知异常不触发第二轮。
- **保存并复用带标注的结果。** 重开不因同一警告再次调用模型。长句保留已完成内容、复用分片，并将警告集中到句尾。存储失败仍保留可见正文，但不假确认已经保存；用户仍可主动重试真正的失败。
- **保留译文，不猜身份。** 保护片段仅还原本句已知标记，不补造缺失数字或引用；未知/重复 ID 不错配，转义 ID 同样检查。推理文本、JSON 外壳及确实没有正文的结果不会冒充译文。取消、GPU 重启、陈旧消息与只阅读安全边界保持。
- 保留 alpha.20 的 TXT 手势、双栏、章节/百分比跳转、全屏控件与安全返回，以及后台断点、PDF 批注/导出。无数据库迁移或清空、模型/提示词/生成语法/全局缓存指纹变化，不删除旧文档、合格译文或模型。

## English

- **Keep usable output for any error, not just protected-token mismatches.** Retain attributable translated text after truncation, timeout or format errors and append the corresponding code, e.g. `Translation… *****（truncated_stream_length）`, `*****（http_500）` or `*****（host_timeout）`. Multiple reasons are merged without duplication.
- **Plain text, normal styling:** PDF/TXT light and dark modes use the surrounding translation's color and font, with no red, underline, bold or special emphasis. Stars are not interpreted as Markdown/HTML.
- **One generation for ordinary errors:** removes batch-to-sentence repair and text-repair loops and disables hidden connection/408/503 API POST retries. Only explicit Vulkan backend compatibility failures may clear the old draft and fall back to CPU once. Truncation, output limits, bad model files and unknown errors do not trigger another generation.
- **Persist and reuse marked output:** reopening does not regenerate an imperfect saved translation. Long sentences reuse completed fragments and consolidate warnings at the end. Storage failures retain visible text without claiming a successful save; explicit user retry remains available for actual failures.
- **Keep text without guessing ownership:** restore only the sentence's known protected tokens, never invent missing references/numbers, and reject ambiguous or foreign IDs, including escaped IDs. Reasoning text, JSON scaffolding and empty results are not presented as translations. Cancellation, restart, stale-message and Read only fences remain.
- Retains alpha.20 TXT gestures, split columns, chapter/progress navigation, fullscreen controls and safe exit, plus background checkpoints and PDF annotations/export. No database migration/wipe or model/prompt/grammar/global-cache-fingerprint change; valid documents, translations and models remain intact.

## Validation / 验证边界

Version21 local validation passed: **149 Android Web + 16 upstream baseline contracts**, complete TypeScript checks and **2 standalone navigation tests**. Core **110 passed / 1 optional real-model test skipped**, App **18 passed**, Engine **11 passed** with a fake runtime. ARM64 native-enabled debug and AndroidTest APK compilation, Lint **0 errors / 54 warnings**, local debug signature, ZIP/ELF **16KiB**, notices and no-model distribution checks passed.

Actual desktop Edge with generated reader/PDF.js resources covers PDF/TXT normal rendering and cached reopening, three distinct mid-generation failures in light/dark mode, plain-text warnings with identical nearby styling, Read only, background acknowledgement barriers, PDF geometry/annotation saving and existing TXT navigation/save-freeze guards. Native/storage/translation faults are simulated. **No Android device/emulator/instrumentation execution, model download or real-model inference was performed for this version.** AndroidTest APK compilation is not execution. Independent implementation review closed all findings; signed CI, retained-certificate verification and full anonymous downloads remain separate mandatory publication gates. See [alpha.21 validation](https://github.com/kejie-tang/pararead-android/blob/main/docs/reports/alpha21.md) and [warning rules](https://github.com/kejie-tang/pararead-android/blob/main/docs/translation-warnings.md).

## Install / 安装

Android 9+ / ARM64, **versionCode 21**. Release APKs must retain certificate SHA-256:

```text
e044c8f329f42465866add4d041f29c498009361241610c8ee2de43ae6e87a45
```

Same-signed previews support in-place upgrades, retaining private documents, translations, models and reading state. **不要为绕过签名错误卸载旧版 / Do not uninstall to bypass a signature error.** The retained preview certificate identifies as Android Debug; this is not a production-signing claim. Local/CI debug APKs may use different keys and are not upgrade substitutes. APK, notices and SHA-256 checksums are attached. No model weights or third-party papers are bundled. Existing alpha.20 and all earlier releases remain unchanged.
