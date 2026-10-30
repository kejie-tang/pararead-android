# alpha.13：全屏工具栏随阅读收起

2026-10-30。基于 `db9cef9`，增加全屏阅读手势和 `versionCode 13` / `0.2.0-alpha.13`。

全屏向上滑继续阅读时，PDF 工具栏和译文顶部栏一起收起；向下滑或点 PDF 区域右上角的小箭头恢复。原生右下角退出按钮始终保留，Android 返回键退出全屏。普通模式不自动收起。收起状态不持久保存，重新打开阅读器时先显示菜单，全屏偏好继续保存。

控件只跟随 PDF 或译文区域的触摸、滚轮和阅读键输入，不根据 scroll 事件切换，避免译文自动对齐、恢复位置和布局变化引发来回闪动。累积约 40 CSS 像素的向前手势收起，反向约 16 像素恢复；横向拖动、双指缩放、批注工具、固定选句、原生文字选择、输入框和打开的菜单阻止收起。箭头锚定实际 PDF 区域，避免桌面最小布局宽度把它挤到译文栏下面。隐藏的工具栏同时设为 inert，Tab/Escape 或窗口尺寸变化会恢复菜单。

原生宿主通过 session.open.settings 和 reader.command 同步全屏状态与中英文按钮名称，不重建 WebView 或翻译会话；无数据库迁移，也没有模型加载、下载或推理参数改动。没有新增后台轮询或逐帧滚动监听。

## 验证

[结构化统计与尺寸](alpha13-checks.json) · [平板测试输出](alpha13-android-tablet.txt) · [手机测试输出](alpha13-android-phone.txt)

环境：macOS、Node.js 24.15.0、JDK 21、Android SDK 36；API 36 ARM64 模拟器 / WebView 133。平板为 1920×1200 / 240dpi，手机为 1080×2400 / 420dpi。

- TypeScript 检查、21 项 Android Web 测试、16 项上游契约、52 项 core 测试及 8 项 engine JVM 测试通过。
- debug APK、Android 测试 APK、release APK 编译和 Lint 通过。详细警告统计见结构化结果，不宣称零警告。
- [实际 PDF.js Chromium 触摸回归](alpha13-browser.json)通过，错误列表为空。验证原文上滑收起、反向滑动恢复、译文侧手势、箭头唤回、窄屏箭头边界、选句/原生文字选择/批注保护、自动滚动不触发、退出全屏恢复，以及旧的缓存恢复、双向选句和增量批注保存。浏览器中原文与译文区域分别增加 32 和 33 CSS 像素高度。
- Android 平板布局 10 个 instrumentation 用例通过；手机布局重复运行其中 4 个阅读器用例，全部通过。覆盖真实系统输入手势、两侧菜单、箭头点击、固定选句保护、WebView 内标记保持、Activity 重建、原生返回键、译文恢复、整篇任务恢复、批注和界面语言。
- Android 的点击用例先等待 WebView 惯性滚动停止，避免把系统用于停止 fling 的首个点击当成按钮点击。

测试使用项目原创指南、Mock 或预置缓存；JNI 加载由既有集成用例检查。本次没有进行天玑 9400 实体设备性能、真实模型翻译质量或实体笔验收。

## 安装与附件

预览版沿用 alpha.11/alpha.12 的持久证书，SHA-256 为 `e044c8f329f42465866add4d041f29c498009361241610c8ee2de43ae6e87a45`。证书身份仍为 Android Debug。签名不变，覆盖安装保留 App 私有文档、模型和已保存译文。

本机 release APK 经发行打包脚本检查：只包含项目原创示例，不含 GGUF，保留许可和实际运行时依赖清单；JNI 为 ARM64，ELF 满足 16 KiB 对齐。发布的实际校验摘要以附件 `SHA256SUMS.txt` 为准。

[平板收起后的界面](alpha13-hidden-controls-tablet.png) · [手机收起后的界面](alpha13-hidden-controls-phone.png) · [浏览器触摸截图](reader-hidden-controls.png)。均为原创指南与 Mock/缓存演示，不是实体平板或翻译质量展示。

本机实际签名 release APK 也执行了独立入口用例：[平板 1 项](alpha13-release-tablet-entry.txt)、[手机重复 1 项](alpha13-release-phone-entry.txt)均通过。在文档尚未加载完时通过普通界面立即进入全屏，等待打开后使用系统滑动收起、点击箭头恢复，再用原生返回键退出；不依赖 debug 模式的 `__bilingual` 钩子。上面的两张 Android 截图取自该 release APK，等待渲染完成后捕获。

如果恢复了之前固定选中的句子，菜单按设计保持可见；取消选句后继续自动收起。
