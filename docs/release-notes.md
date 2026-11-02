# ParaRead Android 0.2.0-alpha.17 — 翻页提速与缓存复用 / page priority and cache reuse

## 中文

- **取消提示不再常驻。** “全篇已取消”约 2.5 秒后自动消失，并移除空白状态行。新的进度或错误不会被旧提示的计时器清除；书库中的可继续任务状态仍然保留。
- **全篇优先复用已有译文。** 保留有效译文与缓存指纹，只补缺失或未通过质量校验的句子。缓存恢复按桥接批次一次查询（每批最多 40 句），减少逐句读取和线程切换；内存中已完成的句子不再重复查库。
- **跨页自动翻译优先当前页。** 当前页自动请求可抢占低优先级的全篇/邻页预取推理，无须再点击句子来提升优先级。先等待旧推理安全释放资源，再执行当前页；不打断更高优先级的显式选中请求。全篇任务随后继续，已提交译文不会重新推理。
- **保留可靠进度。** 当前页与全篇请求交接时，旧回复不能覆盖新请求；当前页临时失败后，全篇续跑仍可恢复有效结果。分页落库和任务进度仍等待确认，不提前计为成功。
- 保留 alpha.16 的书库后台全部翻译、英文照抄质量检查，以及既有阅读位置、缓存、批注和全屏交互。手动翻译模式仍需显式操作，不会被改成自动模式。

## English

- Whole-document cancellation feedback dismisses after about **2.5 seconds**, without leaving an empty status row. A stale timer cannot clear newer progress/errors; resumable library job state remains durable.
- Whole-document translation reuses valid saved results and requests only missing/invalid sentences. Cache-only restoration uses one bounded Room query per bridge batch (at most 40 sentences), skipping in-memory completed results. No fingerprint bump or cache wipe.
- Automatic current-page work can preempt lower-priority whole-book/prefetch inference, without a sentence click. Cancellation is drained before the next inference owner starts. Explicit selection keeps its higher priority; preempted whole-book work resumes using committed cache rows.
- Ownership and durable checkpoint acknowledgements are preserved. Resumed whole-book output can recover a failed visible-page request, but cannot overwrite a newer pending/successful owner.
- Retains alpha.16 background library translation, source-copy validation, reading restoration, annotations and fullscreen behavior. Existing manual-mode preferences remain explicit.

## Validation / 验证边界

Local validation includes **42 Web/host-contract tests**, **16 upstream-contract tests**, **72 Core JVM tests (71 passed, one opt-in real-model test skipped)** and **9 App JVM tests**. Priority tests cover all 16 incoming/running combinations, queue ordering, joined cancellation and completed-cache reuse. Whole-book tests verify zero inference requests when fully cached, and only missing sentences when partially cached. Actual Edge/PDF.js checks include automatic page 2/page 6 translation scheduling, cache reopening, selection/fullscreen and annotation saving.

A **read-only Android API 37 x86_64 emulator** ran the local patched debug smoke build with the explicitly labelled **Mock** provider and the original approximately **15 KB / six-page PDF**. Screenshots show cancellation feedback visible shortly after cancellation and absent later, without a blank row; entering page 6 without pressing a translation button produced automatic mock results. This debug smoke build intentionally excluded native libraries; native-enabled compilation, Lint and packaging are checked separately. It is not a signed-upgrade package or proof of native GGUF inference.

No real model inference, model download, physical-device or instrumentation execution was performed for these follow-up fixes. The earlier user-approved tiny two-thread qwen3:0.6b CPU test remains alpha.16 evidence, not a new alpha.17 performance measurement. No immediate-result timing guarantee is made: extraction, safe cancellation and model speed still apply. See [validation details](https://github.com/kejie-tang/pararead-android/blob/main/docs/reports/alpha17.md).

## Install / 安装

Android 9+ / ARM64, **versionCode 17**. The signed release retains certificate SHA-256:

```text
e044c8f329f42465866add4d041f29c498009361241610c8ee2de43ae6e87a45
```

Install over the previous same-signed preview to retain private PDFs, translations, models and reading state. **Do not uninstall to bypass an upgrade/signature error.** APK, notices and SHA-256 checksums are attached. Model weights and third-party papers are not bundled.
