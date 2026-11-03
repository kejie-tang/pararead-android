# ParaRead Android 0.2.0-alpha.22 — 原文直译与兼容性优先 / Original text & compatibility first

## 中文

- **原文直接进入模型。** 不再用 `__PR…__` 替换数字、公式、引用、URL 或代码，不做标记还原，也不拒绝原文中的字面标记。长句仅按上下文预算连续分片，拼接与原文完全一致且不拆 Unicode 代理对。
- **不干预译文质量。** 删除保护内容、逐字复制标记、禁止总结、否定/转折等额外训令；默认提示词只保留翻译任务、目标语言及必要输出协议，非空的用户术语表仍可使用。不再检查译文语言、原文复制、数字或引用，不生成 `protected_token_mismatch` / `translation_language_or_source_copy`，也不因译得不准自动修复重译。测速与兼容测试同样不再以“必须有中文”判断成功。
- **只保留运行与协议边界。** 仍检查非空正文、大小限制、唯一且已知的句子 ID、JSON/字符串格式及推理内容隔离。JSON 译文中的字面 `<think>` 标签不会被误删；外部推理包装与无归属内容不冒充译文。保留取消、抢占、排空、单推理所有者与安全保存。
- **错误尾缀没有星号。** 截断、HTTP、超时等运行失败已有可安全归属的正文时保留正文，普通显示为 `译文（实际错误码）`；PDF/TXT 浅深色均不额外标红、加下划线或强调。普通失败只生成一次，不隐式重发 HTTP；只有明确的后端兼容故障允许清除旧 GPU 草稿后回退 CPU。
- **兼容旧缓存，不清库。** 新提示词/校验器为 `7/7`，优先当前键，只读回退同模型、模型哈希、语言和术语表的已知 `6/6` 旧键。隐藏旧质量码、去掉旧占位星号，保留真实运行错误与正文；不回写/晋升旧记录，不猜补残缺旧标记。已有缓存不会自动变成新提示词的译文。旧全篇任务游标仍要求精确配置指纹，不静默改配置重跑。
- 保留既有 PDF/TXT 手势、全屏、只阅读、批注/导出、后台断点与保存确认。没有数据库迁移、缓存清空、模型下载或模型模板/native grammar 更改。alpha.21 及更早发布保持不变。

## English

- **Translate original text directly.** No `__PR…__` substitution or restoration for numbers, equations, references, URLs or code; literal marker-like source text is allowed. Context-sized fragments preserve the exact source when rejoined and never split surrogate pairs.
- **No translation-quality policing.** Removes marker-copying and content-preservation/no-summary/negation/contrast instructions. Default prompts retain only the translation task, target language and required output protocol; explicitly configured nonempty glossaries remain available. No language, source-copy, number or citation judgement, no protected-token/language quality warnings and no repair generation for inaccurate output. Speed/compatibility checks no longer require Chinese characters.
- **Keep runtime/protocol boundaries.** Nonempty bodies, size limits, unique known sentence IDs, JSON/string types and reasoning isolation still apply. Literal think tags inside JSON translation strings are preserved; external reasoning and unowned content are not used as translations. Cancellation, preemption, draining, single inference ownership and acknowledged saving remain.
- **No literal placeholder stars.** Runtime failures with attributable text keep it as ordinary `Translation（actual_error_code）`, with no special red, underline or emphasis in PDF/TXT light/dark mode. Ordinary failures generate once without hidden HTTP replay; only explicit backend compatibility failures may clear GPU drafts and fall back to CPU.
- **Read-only legacy cache compatibility.** New prompt/validator `7/7` keys take priority, with a bounded same-configuration `6/6` fallback. Hide old quality codes/stars while retaining text and actual runtime errors. No rewriting/promotion, cache wipe, database migration or guessed restoration. Reused old translations do not automatically become new-prompt results. Whole-document cursors still require an exact configuration fingerprint.
- Retains existing PDF/TXT interaction, fullscreen, Read only, annotations/export, background checkpoints and save acknowledgements. No model download or model-template/native-grammar change. Alpha.21 and earlier releases are unchanged.

## Validation / 验证边界

The version22 validation gates require a full forced local rerun: Core **126 passed / 1 optional model test skipped**, App **18 passed**, Engine **11 fake-runtime tests**; **150 Android Web + 16 historical upstream contracts**, TypeScript and **2 navigation tests**. ARM64 native Debug and AndroidTest APK compilation, Lint **0 errors / 54 warnings**, local debug signing, ZIP/ELF **16KiB**, notices and no-model distribution checks are included. The final version-bound record is [alpha.22 validation](https://github.com/kejie-tang/pararead-android/blob/main/docs/reports/alpha22.md).

Generated-resource desktop Edge covers normal PDF/TXT, Read only and background acknowledgement barriers, ordinary runtime-error styling and existing navigation/save/annotation guards. Native/storage/translations/faults are simulated. **No Android device/emulator/instrumentation execution, model download or real-model inference is claimed.** Compiling model/instrumentation tests is not executing them. Independent release review, exact-source remote CI, retained-certificate signing and complete anonymous downloads are separate mandatory publication gates.

## Install / 安装

Android 9+ / ARM64, **versionCode 22**. Release APKs retain preview certificate SHA-256:

```text
e044c8f329f42465866add4d041f29c498009361241610c8ee2de43ae6e87a45
```

Same-signed previews support in-place upgrades without deleting private documents, translations, models or reading state. **不要为绕过签名错误卸载旧版 / Do not uninstall to bypass a signature error.** The retained certificate identifies as Android Debug; this is not a production-signing claim. Local/CI debug APKs are not public-upgrade substitutes. APK, notices and checksums are attached; no model weights or third-party papers are bundled.
