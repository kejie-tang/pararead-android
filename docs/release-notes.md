ParaRead Android 0.2.0-alpha.7 — development preview / 开发预览版

A calmer interface for reading on an Android tablet: a card-based library, a dedicated sidebar, and consistent model and settings panels.

- Refresh the library with document cards, a clear PDF import action, and a sample-document entry.
- Add a tablet sidebar that becomes compact while reading; use a top navigation bar on narrower screens. Keep reading actions beside the document title on wide screens.
- Introduce coordinated light and dark themes that follow the system, with restrained blue accents, original outline icons, and consistent type and spacing.
- Group settings into translation engine, language/reading, and expandable advanced controls. Replace language-code text fields with named language pickers; CPU threads and terminology remain available under Advanced settings.
- Restyle model management and Hugging Face search. Preserve the two recommended downloads, offline switching, per-model deletion, progress, retry and license links.
- Present translation and raw-engine speed results as readable metric cards. Detailed timings, samples and translation can be expanded; JSON export remains available.

重新设计书库、平板侧栏、模型管理和设置页，统一留白、字体、圆角与图标，新增随系统切换的深色主题。设置中的线程数、术语表和性能记录移入“高级设置”；测速结果突出显示翻译速度与 tg128，详细数据与译文可展开查看。

The visual direction takes inspiration from PocketPal's restrained surfaces and accent colors; icons, illustrations and layouts are authored for ParaRead. Screenshots and verification: [UI refresh](https://github.com/kejie-tang/pararead-android/blob/main/docs/reports/ui-refresh.md).

Android 9+ / ARM64. Debug-signed preview, versionCode 7, using the same local certificate as alpha.6. APK, SHA256SUMS.txt and a notices ZIP are attached. Model weights and third-party papers are not bundled. The model library and translation-cache version are retained from alpha.6.

Validation uses an ARM64 Android emulator and does not establish physical-tablet performance or translation quality. Qwen3-1.7B remains the default; Qwen3-0.6B is available for quick preview reading. Research-only RWKV and specialized NanoTranslator adapters remain disabled.
