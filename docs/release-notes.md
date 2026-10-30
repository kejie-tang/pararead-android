# ParaRead Android 0.2.0-alpha.12 — restored translations and full-screen reading / 译文恢复与全屏阅读

## 中文

- **重开 PDF 恢复译文。** 页面就绪后主动读取已保存的译文，手动翻译模式也能恢复显示。恢复只查本地缓存，不调用模型或 API；自动范围只补译未命中的句子。
- **翻回旧页也能恢复。** 页面状态被内存淘汰后，再返回该页会重新读取持久缓存；过期会话和迟到回复不会覆盖新状态。
- **可选全屏阅读。** 阅读工具栏新增“全屏”，隐藏左侧导航、顶部应用栏、状态信息及系统栏，保留 PDF.js 的翻页、缩放和译文开关。右下角按钮或 Android 返回键退出；记住偏好，切换不重建 WebView、不取消翻译。
- 无数据库迁移；既有已提交译文按当前模型、语言及术语表等配置复用。译文仍在 App 专属数据库中，不会自动嵌入导出的批注 PDF。

## English

- **Restore translations when reopening a PDF**, including manual mode. Cache-only restoration does not call the model/API; automatic modes translate only missing sentences.
- **Restore evicted pages** when returning to them, with deduplicated lookups and stale-reply protection.
- **Optional full-screen reading** hides app navigation, toolbar, status text and system bars. PDF controls remain available. Exit with the bottom-right button or Android Back; the preference persists without recreating the reader or cancelling translation.
- Existing committed translations remain usable with matching translation settings. No database migration or embedded-translation PDF export is introduced.

## Validation / 验证范围

Local checks passed: TypeScript, **21 Android Web tests**, **16 upstream source-contract tests**, the real PDF.js browser harness, **52 Kotlin core tests**, **8 engine JVM tests**, app/test APK compilation and Android Lint (0 errors, 45 warnings).

On an **API 36 ARM64 Android emulator**, 9 instrumentation tests passed in tablet layout; the 3 reader recovery/full-screen tests also passed in phone layout. They verify visible Room translations after reopening without additional inference, returning through the library, full-screen dimensions and system bars, the same WebView, exit/Back handling, task recovery, annotations and language settings. Translation fixtures use Mock or seeded cache; this is not physical-device performance or model-quality validation.

The actual GitHub release APK passed checksum/signature/alignment checks, in-place installation and a separate reader UI smoke test. Both the signed release workflow and Android CI passed for application commit `69f9e3a`. The subsequent test/CI update adds instrumentation APK compilation; emulator tests were run locally.

[Android validation, signing and upgrade evidence](https://github.com/kejie-tang/pararead-android/blob/main/docs/reports/alpha12-local-validation.md) · [Implementation notes](https://github.com/kejie-tang/pararead-android/blob/main/docs/reports/reader-recovery-fullscreen.md).

## Install and data safety / 安装与数据安全

Android 9+ / ARM64, **versionCode 12**. The GitHub alpha.12 release APK uses the same persistent preview certificate as the alpha.11 Release APK. An in-place update was verified on the ARM64 emulator, retaining documents and the installed model. This does not cover temporary-key debug APKs downloaded from other CI runs.

Certificate SHA-256: `e044c8f329f42465866add4d041f29c498009361241610c8ee2de43ae6e87a45`. The certificate has an Android Debug identity and is retained for preview upgrades. **Do not uninstall an existing version merely to bypass a signature mismatch**: uninstalling deletes private PDFs, saved translations and models.

已确认 GitHub alpha.12 Release APK 与 alpha.11 Release APK 同签名，模拟器覆盖升级保留文档和模型。此结论不适用于使用临时密钥的 CI debug APK。

这是开发预览版。旧版已完成、校验并提交的译文可恢复；退出时仍在生成中的草稿不保证保存。模型权重和第三方论文不随 APK 分发。
