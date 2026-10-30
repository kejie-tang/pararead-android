# Alpha.14：继续阅读与 PDF 列表书库

## 行为与存储

- 用单列文档行替换原来的网格，显示首页缩略图、最多两行标题、文件大小、已知页数、阅读位置和已保存译文标记。顶部卡片选择最近打开过的文档，整卡点击恢复阅读；未打开的新导入文档不会取代它。空书库保留示例引导。
- 缩略图由 Android PdfRenderer 在 IO 线程串行生成，只渲染第一页，最大 240×360，内存缓存上限 4 MiB，磁盘位于 App cache。继续阅读与列表复用预览；加密/损坏文件使用图标。删除文档时清除对应预览。
- Room 表结构和数据库版本保持不变。读取原有 ReadingRecord；在其 JSON 中补充 pageCount 和 translationsSaved。旧记录只有页码时仍可继续阅读，首次打开后补齐总页数。
- 完成 reader session.opened 后更新最近打开时间。Room Flow 让返回书库、重命名和进度更新自动刷新。已验证的译文提交或恢复缓存成功后记录保存标记；该标记表示存在已保存译文，不表示全篇完成，也不保证不同模型/语言配置之间复用。
- 重复导入按 SHA-256 去重，保留已有自定义名称、进度与最近阅读时间。重命名只改书库标题。
- 删除先通过 Room 事务移除该文档及其阅读、页面解析、全篇任务、批注草稿索引和性能记录，再清理 App 私有文件；不访问外部原始 URI。句级翻译缓存由多份 PDF 共享，不随单份文档删除；确认对话框明确说明保留范围。
- 修复 PDF.js 异步 setInitialView 在恢复页码后再次初始化而跳回第一页的问题：加载前传递原生保存的 initialBookmark/initialRotation，等初始视图完成后再恢复位置并接入进度保存。
- ReaderHost 用互斥更新和不可取消的数据库提交保存阅读 JSON；退出时等待旧 reader scope 完成后再允许删除或打开另一份文档。

## 验证

环境：macOS、Node.js 24、JDK 21、Android SDK 36、API 36 ARM64 模拟器。

- Debug/release APK 与 Android 测试 APK 编译、Android Lint、core 测试通过。Lint 保留既有依赖更新与兼容性警告，不宣称零警告。
- 9 项 Android instrumentation 回归通过：DocumentLibraryTest、ReaderRecoveryUiTest、InterfaceLanguageTest。覆盖同内容重导入、最近打开顺序、重命名保留进度、原件保留、删除隔离、共享译文保留、缩略图生成与清理、真实 PDF 页码恢复、译文恢复不推理、全屏与菜单语言。
- alpha.14 的书库完整交互另在手机 1080×2400 / 480dpi（360dp 宽）与平板 1920×1200 / 240dpi（1280dp 宽）各通过 1 项，包含打开第 2 页、返回、重命名、中英文 Activity 重建和删除确认。[手机输出](alpha14-android-phone.txt) · [平板输出](alpha14-android-tablet.txt)。
- TypeScript、Android Web 回归和真实 PDF.js Chromium 触摸测试通过，浏览器错误列表为空；涵盖双向选句、手动缓存恢复、全屏手势、窄屏工具栏和增量批注导出。[浏览器结果](alpha14-browser.json)。
- 本机实际签名 release APK 也通过普通书库入口、提前进入全屏、滑动收起、箭头恢复与返回退出测试。[release 输出](alpha14-release-entry.txt)。
- [英文平板截图](../screenshots/library-en.png) · [中文平板截图](../screenshots/library-zh.png)。截图来自实际模拟器，使用临时生成的测试 PDF 和预置译文保存状态。

本次验证聚焦书库管理与阅读恢复，未重新测试实体设备推理性能或模型翻译质量。

## 签名

alpha.14 沿用既有持久预览证书，SHA-256 为 `e044c8f329f42465866add4d041f29c498009361241610c8ee2de43ae6e87a45`。本机 release APK 签名与 ZIP 16KiB 对齐检查通过；发行打包脚本核验 ARM64 JNI、ELF 对齐、许可和唯一原创示例。公开发布附件的 SHA-256 以该版本 SHA256SUMS.txt 为准。
