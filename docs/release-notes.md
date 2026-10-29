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

Local checks: TypeScript, **21 Android Web tests**, **16 upstream source-contract tests**, reader asset build and the real PDF.js browser harness passed. The browser harness verifies visible translations after reopening in manual mode with zero new translation requests, bidirectional selection and incremental annotation saving. It uses desktop Edge/Chromium touch emulation, not Android hardware.

New Android instrumentation coverage for Room restoration/full-screen UI and a Kotlin protocol assertion are included. The local Windows environment has no JDK/Android SDK; native build results are established separately by GitHub Actions. CI does not run Android instrumentation or physical-device validation.

[Implementation and local evidence](https://github.com/kejie-tang/pararead-android/blob/main/docs/reports/reader-recovery-fullscreen.md).

## Install and data safety / 安装与数据安全

Android 9+ / ARM64, **versionCode 12**. APK signing compatibility with alpha.11 has not been established. **Do not uninstall an existing version merely to bypass a signature mismatch**: uninstalling deletes private PDFs, saved translations and models. A compatible in-place update requires the original signing identity. Read the final artifact/signing notes before installing.

这是开发预览版。旧版已完成、校验并提交的译文可恢复；退出时仍在生成中的草稿不保证保存。模型权重和第三方论文不随 APK 分发。
