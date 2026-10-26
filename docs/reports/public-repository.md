> 历史记录：本文描述 3df1571 的整理范围。后续已替换内置示例并准备 GitHub 上传，见 [首次上传验证](public-upload.md)和[发布指南](../publishing.md)。

# 公开仓库说明与许可披露准备

日期：2026-10-26。推荐仓库名 `pararead-android`、显示名称 ParaRead Android，明确独立、非官方 Android 平板适配。没有创建或发布远端仓库。

## 本次整理

- README 改为公开项目说明：来源关系、主要功能、新增/复用范围、开发状态、下载/联网边界、构建和许可入口。
- 保留根 Apache-2.0 LICENSE 原文及其原版权，不用本项目名字替换上游署名；根 NOTICE 逐字包含 ParaRead 原 NOTICE 后追加 Android 修改范围。
- 增加 THIRD_PARTY_NOTICES.md，将 ParaRead/vscode-pdf/PDF.js、llama.cpp 内部组件、Android 依赖、模型和示例论文区分披露。
- 复制 11 份 PDF.js 许可原文至 third_party/pdfjs/，SOURCES.json 保存原始路径与 SHA-256。
- 默认模型许可取自已固定权重所对应的实际 Ollama license layer，验证 SHA-256 `d18a5cc71b84bc4af394a31116bd3932b42241de70c77d2b76d69a314ec8aa12`，保留 `Copyright 2024 Alibaba Cloud`；没有引入模型权重。
- 修改过的 TS/配置和生成的 HTML/JS 增加修改标记。构建把根 LICENSE/NOTICE/第三方总声明一起打包，保留现有字体、CMap、ICC、WASM 原文。
- third_party 文件按原字节保存；.gitattributes 禁止对这部分做行尾转换，并保留上游原有空白。

## 已执行验证

| 范围 | 结果 |
| --- | --- |
| 修改说明对应的代码检查 | 对比固定 ParaRead 源，修改文件有标记，未修改文件保留原文 |
| TypeScript 类型检查 | 通过 |
| JS/bridge 回归 | 13 项通过；[输出](public-repo-web-check.txt) |
| Debug APK 构建与 Lint | 构建通过；Lint 0 错误、34 条警告；[输出](public-repo-build.txt) |
| 项目内 Markdown 链接 | 新整理的 4 份文档共 63 个本地链接均存在 |
| 原文核对 | 11 份 PDF.js 许可与固定源码字节一致；默认模型 license layer 摘要匹配；原 ParaRead NOTICE 完整保留；根 LICENSE 未变 |
| APK 中的通知 | 27 项逐字校验：3 个根通知、11 份 PDF.js 许可的两处副本、2 个模型通知文件 |
| APK 签名和 16KiB 对齐 | 通过；最新大小/摘要见 [verification.json](verification.json) |

本次只修改披露文档、来源标记和通知打包。前一版本的 Kotlin/Android/Chromium 运行证据保留其原范围，本次未重跑真模型或设备测试。

## 仍需处理的公开分发事项

当前构建会把 PDF.js 的 tracemonkey 论文打入 APK，历史截图也显示论文内容。论文有独立版权条件；本次没有取得额外授权或替换该文档，不能把它视为 Apache-2.0 资源。公开分发相应 APK/截图前应替换或核实其条件。

正式 Release 还需按实际 variant 复核传递/原生依赖通知、选择发布签名，并完成 README 所列真机与功能验收。当前许可披露覆盖已查明的引入范围，不把 debug 依赖树当作全部资产的分发授权。名称、简介和操作说明见 [publishing.md](../publishing.md)。
