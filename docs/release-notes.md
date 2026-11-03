# ParaRead Android 0.2.0-alpha.20 — 阅读手势与安全返回 / Reader gestures & safe navigation

## 中文

- **PDF 更清爽。** 全屏阅读时，工具栏折叠后不再显示向下箭头；反向滑动或 Tab/Escape 可恢复。保留批注、文本选择及输入时的保护。
- **PDF/TXT 移除全屏退出浮钮。** 右下角不再显示 ×；安卓返回先退出全屏，再次返回书库。宽屏进入文档后隐藏左侧书库、模型及设置导航，回到书库后恢复。
- **TXT 左右滑动翻页。** 正文左滑下一内容页、右滑上一页；上下滑动仍为页内阅读。避让两侧安卓返回手势区域，不干扰选中文本、双指操作或输入控件。
- **横屏双栏可调。** 拖动中央分隔条调整原文/译文比例，支持键盘调节并记忆设置；比例限于 25%–75%，不改变内容页、句子标识、译文缓存或全篇断点。隐藏原文及只阅读保持单栏。
- **TXT 工具栏可快速收起。** 全屏点击“显示阅读工具栏”后，可直接“收起阅读工具栏”，无需退出全屏。
- **章节与百分比定位。** 新面板支持向下滚动章节、分批加载和失败重试；百分比滑块/数值先预览，再点击跳转。无章节可按百分比定位，单页文档不提供无效操作。0% 为第一页，100% 为最后一内容页。
- **返回前确认保存。** 退出文档前等待阅读进度及必要的 PDF 批注保存成功；失败保留当前文档和编辑。保存冻结期间阻止键盘及其他翻页路径，避免丢失刚调整的分栏或阅读位置。
- 保留 alpha.19 的 TXT 编码导入、固定内容分页、PDF/TXT 只阅读和后台翻译。无数据库迁移或清空、模型引擎/提示词/全局缓存指纹变化，不清除旧文档、合格译文或模型。

## English

- **Cleaner PDF fullscreen:** removes the collapsed-toolbar down-arrow overlay. Reverse scrolling and Tab/Escape restore tools; annotation, selection and input guards remain.
- **No fullscreen exit X:** use Android Back to leave fullscreen, then Back again to return to the library. The wide library/model/settings sidebar is hidden while a PDF/TXT document is open and restored in the library.
- **TXT page swipes:** swipe left/right through stable content pages; vertical gestures still scroll within the page. Screen edges remain reserved for Android Back; selections, multiple touches and interactive controls do not turn pages.
- **Resizable landscape columns:** drag or keyboard-adjust the original/translation split, with a remembered 25%–75% source share. Page identities, translation caches and checkpoints are unchanged. Hidden-source and Read only stay single-column.
- **Quick TXT toolbar dismissal:** shown fullscreen controls now have a matching Hide reading toolbar action.
- **Chapters & progress:** a scrollable, lazy-loaded chapter panel with retry, percentage preview and explicit navigation. Books without detected chapters retain progress navigation; single-page books disable meaningless controls. 0% is the first content page and 100% the last.
- **Save before leaving:** reading-state and necessary PDF annotation acknowledgements precede detach. Failed saves retain the current document and edits; all TXT navigation paths are fenced during save freeze.
- Retains alpha.19 TXT encoding import, stable pagination, shared Read only and explicit background translation. No database migration/wipe, engine/prompt/global-cache-fingerprint change, or deletion of valid documents/translations/models.

## Validation / 验证边界

**128 Android Web tests + 16 upstream/source contracts**, TypeScript and the standalone **2-test chapter/progress suite** passed. Native JVM checks: Core **93 passed / 1 optional real-model test skipped**, App **18 passed**, Engine **9 passed** using a fake runtime. Full ARM64 debug APK and AndroidTest APK builds, Lint **0 errors / 54 warnings**, debug signature, ZIP/ELF **16KiB** alignment, notices and no-model distribution validation passed. Independent review closed both save-boundary findings; no open P0–P2 blocker.

Actual desktop Edge with generated reader resources covers normal PDF, original-only PDF, headless PDF, TXT touch navigation, split drag/keyboard/reopen, fullscreen toolbar dismissal, 205 chapters, percentage navigation, empty/single-page cases, save-freeze faults, cache retention and background acknowledgement barriers. Layout checks include phone landscape, tablets, 320px width, 200% UI text and light/dark themes. Native/storage/translation/save-fault behavior is simulated. **No Android device/emulator or instrumentation execution, model download or real-model inference was performed for this version.** AndroidTest regressions for immediate Back and failed-exit retention were compiled only. Signed CI remains a separate required gate; see [alpha.20 validation](https://github.com/kejie-tang/pararead-android/blob/main/docs/reports/alpha20.md).

## Install / 安装

Android 9+ / ARM64, **versionCode 20**. Release APKs must retain certificate SHA-256:

```text
e044c8f329f42465866add4d041f29c498009361241610c8ee2de43ae6e87a45
```

Same-signed previews support in-place upgrades, retaining private documents, translations, models and reading state. **不要为绕过签名错误卸载旧版 / Do not uninstall to bypass a signature error.** The retained preview certificate identifies as Android Debug; this is not a production-signing claim. Local/CI debug builds may use different keys and are not upgrade substitutes. APK, notices and SHA-256 checksums are attached. No model weights or third-party papers are bundled. Existing alpha.19 and earlier releases remain unchanged.
