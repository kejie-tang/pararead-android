# Phase 2 验证报告

日期：2026-10-26。交付：可安装的 Kotlin/Compose Android 开发版，复用固定 ParaRead TS/PDF.js，单个 WebView 保留逐句双栏交互。主要原型出口通过模拟器，实体平板未测。

## 实现

SAF PDF 导入/私有流式快照、受限随机 range 通道、离线 worker/字体/样式、句子坐标/结构块、双向选句高亮/连接线/同高同步、触摸 tap 与非 touch hover、Mock 后端、范围设置、横竖屏分隔条。工具栏按 PDF 栏宽度响应布局，窄栏缩减页码输入并隐藏总页数标签，保留编辑按钮；浏览器检查工具栏不侵入译文栏。ViewOnLoad 关闭 PDF.js 的独立位置恢复，由 Room 统一管理阅读状态；WebView 初次测量为零高度的情况使用 viewport resize 补偿。

## 实际验证

| 项目 | 结果/证据 |
| --- | --- |
| Android 构建 | `scripts/gradle.sh :app:assembleDebug` 成功；默认包含真实 ARM64 JNI，不使用 skipNative |
| 类型检查 | `npm run check:reader` 通过 |
| 原版契约 | `npm test`：16 通过；GAP 项证明原版问题，Android 修复另由新测试验证 |
| Android 阅读器测试 | `npm run test:android-web`：6 通过，含稀疏存储、补丁漂移、双栏顺序、pending 升级、过期/乱序/重复消息 |
| Chromium 触摸联动 | `node scripts/test_reader.mjs` 通过；逐句独立 committed → terminal，整页 Mock 完成、触屏点原文、点译文回跳、同高误差 ≤4 CSS px、真实 PDF.js 保存后重解析 |
| ARM64 模拟器 | 两项 instrumentation 通过；真实 JNI 版本校验、PDF canvas 可见、明确翻译第一页的所有文本句子、面板激活、Room、批注及 Activity 重建恢复；另测 Long/并发范围读取 |

证据：[构建/设备测试输出](android-build-tests.txt)、[instrumentation XML](android-instrumentation.xml)、[阅读器测试](android-web-tests.txt)、[浏览器 JSON](android-reader-browser.json)、历史 Android 原型截图（含第三方论文，已从公开仓库移除）；当前 [横屏](reader-landscape.png)和[竖屏](reader-portrait.png)为自编示例的 Chromium 回归截图。完整版本及 APK SHA 见 verification.json。

运行环境为 WishLoop_API36 ARM64 AVD，Android 16/API36、WebView 133.0.6943.137；AVD 显示尺寸覆盖为 2560×1600@240dpi，另测 1600×2560，属于模拟器。桌面浏览器截图/测试独立标注，不作为 Android 硬件证据。截图中“模拟译文”不代表中文翻译质量。

## 未完成验收

实体平板、多 DPI/缩放/旋转/跨淘汰页全矩阵、长按/文本选择/pinch 冲突、真实鼠标和触控笔。首次完整页回归发现流式 committed 会过早结束等待，已修复为只由 terminal 结算，并补充整页与负向测试。完整必须项状态以 compatibility.md 为准。
