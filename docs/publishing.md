# GitHub 与 APK 发布

仓库：[kejie-tang/pararead-android](https://github.com/kejie-tang/pararead-android)。显示名称 ParaRead Android，说明为独立、非官方 Android 平板适配。

仓库默认首页为英文 [README.md](../README.md)，中文入口为 [README.zh-CN.md](../README.zh-CN.md)，两份 README 顶部互相链接。更新下载版本、功能状态和许可说明时同步维护两种语言。

## 仓库说明与许可

About：`Unofficial Android tablet adaptation of ParaRead, with sentence-aligned bilingual PDF reading and offline translation via llama.cpp.`

Topics：`android`、`tablet`、`pdf-reader`、`bilingual-reader`、`offline-translation`、`llama-cpp`、`qwen3`、`jetpack-compose`、`pararead`。

Android 原创代码与适配修改继续采用 Apache-2.0。保留根 [LICENSE](../LICENSE)、[NOTICE](../NOTICE)、[第三方声明](../THIRD_PARTY_NOTICES.md)及 `third_party/` 原文。上游名称用于来源说明，不表示官方隶属或背书；名称、商标和第三方资产不会因根许可证而重新授权。

内置 PDF 为本项目自编的 [六页阅读指南](../samples/pararead-reading-guide.pdf)，包括原创文字、表格及矢量图，采用 Apache-2.0，见 [示例声明](../samples/NOTICE)。构建先清理生成目录，再排除 PDF.js 上游的全部 PDF，避免原论文残留在另一路径。公开阅读器截图已用自编示例重新生成；旧论文的两张 Android 原型截图不进入公开源码。

推荐体验论文 *Attention Is All You Need* 仅链接到 [arXiv](https://arxiv.org/abs/1706.03762)，由用户下载后导入，不随源码、APK 或发布附件分发。

首次公开上传采用整理后的单个初始提交，旧开发历史保留在本机 `local/pre-publication-history` 分支。这样旧论文截图不会随公开 Git 历史上传；历史测试报告仍标明原来的执行范围。后续正常提交并推送 main。

`.gitignore` 排除上游下载快照、缓存、构建目录、APK/AAB、GGUF、SDK 本地配置、用户 PDF 和签名密钥。只对自编示例 PDF 开放提交。

## 先下载开发 APK

每次推送 main、提交 PR 或手动触发 [Android CI](https://github.com/kejie-tang/pararead-android/actions/workflows/android.yml)，都会进行源码哈希核验、16 项上游契约、TypeScript、21 项 JS/bridge、Chromium 阅读器回归、Kotlin 核心与引擎测试、ARM64 JNI 构建、Android 测试 APK 编译和 Lint。

成功后：打开本次 Actions 运行 → Artifacts → `pararead-android-debug-arm64` → 解压 ZIP → 安装 `*-debug.apk`。需要登录 GitHub，Artifacts 保留 14 天；自动附带通知 ZIP 和 `SHA256SUMS.txt`。APK 不内置模型；应用内另行下载。

CI debug APK 使用 runner 的临时 debug 密钥。不同运行或本机 APK 的签名可能不同，不能作为稳定的覆盖升级渠道。切换签名可能需要卸载，卸载会删除应用私有文档、译文缓存及模型；先另存需要保留的批注 PDF。发布渠道应使用下面的持久密钥。

这条 CI 没有运行实体平板或 Android 模拟器 instrumentation。浏览器触控模拟不等于 Android 设备验收。

## 首次配置发布签名

Android 安装的 APK 必须签名，后续覆盖升级通常需要相同的应用 ID、相同的签名身份以及递增的 versionCode。请保管并备份自己的发布密钥；仓库不生成或提交生产密钥。[Android 官方签名说明](https://developer.android.com/studio/publish/app-signing)

在本机用 JDK 的 keytool 交互式创建密钥，示例路径位于仓库之外：

```sh
mkdir -p "$HOME/.android/keystores"
keytool -genkeypair -v -keystore "$HOME/.android/keystores/pararead-release.jks" -storetype JKS -alias pararead -keyalg RSA -keysize 3072 -validity 10000
```

按提示填写自己的密码和证书信息，不把密码写入命令或提交文件。备份密钥及密码后，在仓库 **Settings → Environments → New environment → `android-release`** 中配置四个 Environment secrets：

| Secret | 内容 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | 上述 JKS 的 Base64 内容 |
| `ANDROID_KEYSTORE_PASSWORD` | keystore 密码 |
| `ANDROID_KEY_ALIAS` | 示例为 `pararead` |
| `ANDROID_KEY_PASSWORD` | 该 alias 的私钥密码 |

macOS 可将文件直接经管道上传到已授权的 GitHub CLI，避免把 Base64 输出到终端：

```sh
base64 -i "$HOME/.android/keystores/pararead-release.jks" | gh secret set ANDROID_KEYSTORE_BASE64 --env android-release --repo kejie-tang/pararead-android
```

其余密码通过 GitHub 设置界面或 `gh secret set` 的交互输入填写。可为环境配置 reviewer 和 main 分支限制。签名 key 仅在手动 release job 中恢复，完成后删除，不用于 PR 构建。

## 发布一个带版本号的 APK

当前预览为 **`0.2.0-alpha.13`**（versionCode 13）。GitHub release APK 由签名工作流构建，继续使用 alpha.11 Release APK 的持久预览证书；已通过模拟器覆盖升级并保留文档和模型，见[验证记录](reports/alpha13.md)。证书身份仍为 Android Debug，不等同于正式生产签名。下面以 alpha.13 说明发布草稿的流程；后续发布时替换版本号并递增 versionCode。

1. 在 [app/build.gradle.kts](../app/build.gradle.kts) 更新 `versionName`（本例为 `0.2.0-alpha.13`）并递增 `versionCode`（本例为 13）。同步中英文 README 当前状态与下载链接和 [release-notes.md](release-notes.md)，提交并推送 main。
2. 等 Android CI 通过。在已验证的 main 提交上创建 tag；tag 必须等于 `v` 加 `versionName`：

   ```sh
   git tag -a v0.2.0-alpha.13 -m "ParaRead Android 0.2.0-alpha.13"
   git push origin v0.2.0-alpha.13
   ```

3. 打开 **Actions → Signed APK release draft → Run workflow**，分支选 `main`，tag 填对应版本（例 `v0.2.0-alpha.13`），保留 `prerelease=true`。
4. 工作流验证 tag 存在、位于 main 历史、匹配 versionName；重新测试并构建 **release** variant，使用环境密钥签名，校验 APK 签名、ZIP/ELF 16KiB 对齐、ARM64 JNI、唯一自编示例、无 GGUF 和通知原文。
5. 打开 Releases 中新建的 **Draft / Pre-release**，核对版本说明、签名证书摘要、附件和真机测试范围，再点击 **Publish release**。草稿不会自动公开。GitHub Releases 可以直接承载 APK，不需要先上架 Play。[GitHub 官方发布说明](https://docs.github.com/en/repositories/releasing-projects-on-github/managing-releases-in-a-repository)

附件包含 `ParaRead-<version>-android-arm64-release.apk`、相应 `*-notices.zip` 和 `SHA256SUMS.txt`。通知 ZIP 含本次 `releaseRuntimeClasspath` 解析结果；APK 也保留项目和第三方许可。工作流拒绝覆盖已有同名 Release；修正内容应按实际情况使用新版本。

下载后校验完整性：

```sh
shasum -a 256 -c SHA256SUMS.txt
```

摘要验证文件字节；覆盖升级依赖签名密钥。首次正式 APK 应在实体 ARM64 平板验证安装、模型下载、离线推理、导入/批注/导出及任务恢复，再决定是否扩大分发。未完成项见 [任务清单](tasks.md)。

## 本机打包

开发版（不需要发布密钥）：

```sh
npm ci
python3 scripts/fetch_upstream.py
python3 scripts/fetch_llama.py
./gradlew :core:test :app:assembleDebug :app:lintDebug
mkdir -p artifacts
./gradlew :app:dependencies --configuration debugRuntimeClasspath > artifacts/debug-runtime-dependencies.txt
python3 scripts/prepare_apk.py --dependencies artifacts/debug-runtime-dependencies.txt
```

输出在 `artifacts/distribution/`。本机 JDK/SDK 配置见 README。生成示例 PDF 仅在修改示例时需要 `reportlab==4.4.9`；通常构建使用已提交的 PDF，无须安装 ReportLab。

本机签名 release 需要设置 `PARAREAD_KEYSTORE_PATH`、`PARAREAD_KEYSTORE_PASSWORD`、`PARAREAD_KEY_ALIAS`、`PARAREAD_KEY_PASSWORD` 四个环境变量，然后执行 `:app:assembleRelease :app:lintRelease`。四个变量齐全才启用 release 签名；缺失时 Gradle 可能产生 `app-release-unsigned.apk`，它不能直接安装，也不会被打包脚本当成可发布 APK。不要用 debug 密钥替代正式发布密钥。
