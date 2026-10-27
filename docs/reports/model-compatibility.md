# Model compatibility and PocketPal speed investigation — alpha.3

27 October 2026. Local, real GGUF weights, CPU, six inference threads. Test text is original project material; no user documents were sent to a service.

## Why 10 token/s and 22 token/s differ

The user measured ParaRead at about 10 token/s and **PocketPal v1.20.1 (152) Benchmark tg128 at about 22 token/s**, on the same Dimensity 9400 tablet with CPU / six threads. These user observations are not independently reproduced on physical hardware here.

1. **Different workloads:** alpha.2 reported JSON-constrained translation generation (sampling, syntax constraint, callbacks and decoding). PocketPal's benchmark feeds synthetic tokens directly into `llama_decode`; tg128 excludes those translation operations. Native generation token/s already excludes prefill and model loading, so a short input alone does not mathematically account for a twofold difference. Short outputs are more sensitive to CPU ramp-up and have a larger JSON-envelope share.
2. **Measured grammar overhead:** alpha.2 filtered the full vocabulary through GBNF on every token. In the baseline host long-paragraph observation, Qwen3 generated at 29.4 token/s; sampling/grammar consumed about 49% of the measured generation interval. Removing grammar for diagnosis produced 56.0 token/s. Keeping grammar with proposal validation produced 60.0 token/s in the follow-up observation. These are single fixed-seed host observations with different possible outputs, not controlled physical-tablet predictions or statistical speedup guarantees. [Baseline raw data](model-matrix-baseline.json), [proposal sampler with KleidiAI disabled](qwen3-sampling-optimized.json).
3. **ARM build difference:** PocketPal's pinned llama.rn builds dotprod/i8mm variants. ParaRead's previous build used generic ARM64 without KleidiAI. Both already used Release `-O3`; the problem was not an unoptimized native debug build. alpha.3 enables KleidiAI v1.32.0 with runtime CPU dispatch and safe fallback. The host selected DOTPROD kernels for compatible tensor types, but reported no compatible Q4_K kernel on its CPU; gains on Dimensity 9400 still require testing.

alpha.3 retains JSON constraints and the strict validator. It first samples a candidate, checks that candidate against the grammar, and only applies a full-vocabulary grammar mask if rejected, following llama.cpp's common sampler approach. Native output is constrained to the expected IDs and row count. The grammar ends at the closing JSON object, preventing an unbounded trailing-whitespace loop. Placeholder errors remain failures.

### New in-app benchmark

**设置 → 离线 GGUF → 速度测试** runs a 158-word English paragraph once with a fresh provider, three more times with that model loaded, then three raw **pp512 → tg128** engine runs. The reader pauses and caching is bypassed. Native token counts and decode/sampling/callback intervals are exported.

Raw engine settings: ctx4096, batch512, ubatch128, GPU0, F16 KV defaults, one sequence, 16-token warmup, deterministic synthetic token IDs, pp512 followed by tg128. No sampler, grammar or per-token Java callbacks. Displayed rates are **medians of three**; PocketPal reports means. PocketPal uses random IDs and its own engine revision/configuration, so matching token counts and CPU threads does not make the entire runtime identical.

To compare on the user's tablet, use the same GGUF SHA-256, CPU six threads, no GPU, similar temperature/power conditions, and compare PocketPal **tg128** to ParaRead **纯引擎解码 / tg128**. Keep the separately labeled translation rate for actual app work. No 22 token/s achievement is claimed here.

Primary implementation references: [PocketPal v1.20.1 package](https://github.com/a-ghorbani/pocketpal-ai/blob/v1.20.1/package.json), [benchmark presets](https://github.com/a-ghorbani/pocketpal-ai/blob/v1.20.1/src/screens/BenchmarkScreen/BenchmarkScreen.tsx), [llama.rn v0.13.0-rc.7 benchmark](https://github.com/mybigday/llama.rn/blob/v0.13.0-rc.7/cpp/rn-completion.cpp), [ARM variants](https://github.com/mybigday/llama.rn/blob/v0.13.0-rc.7/android/src/main/rnllama/CMakeLists.txt), [llama.cpp common sampling](https://github.com/ggml-org/llama.cpp/blob/2280f0fd163b03d4331d49e08460f66502e35854/common/sampling.cpp).

## Exact weights tested

| Model / source | Quantization | Bytes | SHA-256 |
| --- | --- | ---: | --- |
| Ollama qwen3:1.7b | Q4_K_M | 1,359,279,776 | `3d0b790534fe4b79525fc3692950408dca41171676ed7e21db57af5c65ef6ab6` |
| [LiquidAI/LFM2.5-1.2B-Instruct-GGUF](https://huggingface.co/LiquidAI/LFM2.5-1.2B-Instruct-GGUF) | Q4_K_M | 730,895,168 | `b1b3de114215d9507409a662a501a631095a479a419584e8a2ded6304b19b4f5` |
| [LiquidAI/LFM2.5-350M-GGUF](https://huggingface.co/LiquidAI/LFM2.5-350M-GGUF) | Q4_K_M | 229,312,224 | `7e6f72643caafc9a68256686638c4d7916f2cec76d1df478d4c3ddcd95a6aed4` |
| [ggml-org/Qwen3.5-0.8B-GGUF](https://huggingface.co/ggml-org/Qwen3.5-0.8B-GGUF) | **Q4_0** | 563,036,064 | `57d1997790d1744fba5b40a7317df71ea5e2acee28c47e78f0cce39c0703f8cf` |

Pinned Hugging Face revisions: LFM 1.2B `8ed288026e23958ad9dfa92d53ed773a8eee7125`; LFM 350M `657e078c94084481950a2d555a941481f715536b`; Qwen3.5 `807dfd9a6fadbf703207aac3bfb0b2b3040d077c`. Qwen3.5 was tested as Q4_0 available in that repository, not Q4_K_M. LFM weights have a custom **LFM1.0** license; Qwen3.5 weights declare Apache-2.0. App code licensing does not replace model licensing. No weights are committed or bundled.

The pinned llama.cpp supports `lfm2` and `qwen35`. Templates were checked against each GGUF's actual `tokenizer.chat_template`: LFM2.5 uses ChatML newlines with its GGUF-added BOS, and no Qwen-only `/no_think`; Qwen3.5 uses a closed thinking prefill. This implementation is text-only; it does not load a vision projector.

## Measured output compatibility

Final **M1 Pro host / production JNI** format observations (four fixtures per mode):

| Model | With app JSON grammar | Without grammar (diagnostic) | Production queue with protected spans |
| --- | --- | --- | --- |
| Qwen3 1.7B Q4_K_M | 4/4 | 3/4 | Passed |
| LFM2.5 1.2B Instruct Q4_K_M | 3/4 | 2/4 | Failed after bounded repair |
| LFM2.5 350M Q4_K_M | 3/4 | 1/4 | Failed after bounded repair |
| Qwen3.5 0.8B Q4_0 | 4/4 | 4/4 | Passed |

Both LFM failures with grammar were exact placeholder mismatches. Example: the 1.2B model changed `⟦P0⟧` to `⟨P0⟩`; 350M omitted protected values. The queue's single-sentence repair did not recover all inputs in this fixture. Syntax constraints repair shape and count; they do not supply missing meaning or placeholder fidelity.

[Final host raw matrix](model-matrix-optimized.json) retains every raw output and failure, plus the production queue's bounded single-sentence repair attempts. Fixtures cover a short sentence, the long paragraph, four sentences with numbers/citation/equation/URL/code and glossary, and quoted text/newlines. Each is tested with the app grammar and without grammar for diagnosis. Sampling is temp0.2/top-k20/top-p0.8/seed42, matching the app, rather than a model-specific tuning sweep.

A successful format check means parseable JSON, expected IDs/count, exact placeholder retention and a Chinese-text check. It does **not** certify semantic accuracy or completeness. The 350M short response omitted most of the source even when its format passed. Some 1.2B and 0.8B quoted-text responses omitted a sentence, and some numeric statements were awkward or misleading despite retaining all placeholders. The default model is unchanged; LFM2.5 download support remains experimental.

## Android observations and verification

API 36 ARM64 emulator (`Google sdk_gphone64_arm64`, 8 virtual CPUs / 6 GiB), six CPU threads. These are **not Dimensity 9400 measurements**.

- All three new models loaded and translated the short fixture. The two-sentence protected fixture passed for [Qwen3.5](android-compat-Qwen3.5-0.8B-Q4_0.json), and was rejected for [LFM 1.2B](android-compat-LFM2.5-1.2B-Instruct-Q4_K_M.json) and [LFM 350M](android-compat-LFM2.5-350M-Q4_K_M.json) due to placeholder mismatch, matching the host conclusion. Smoke tests deliberately record these compatibility failures; their JUnit success only certifies loading/short-format operation, not that every fixture passed.
- A real Hugging Face search and **229,312,224-byte LFM 350M download through Android DownloadManager** completed with pinned revision, SHA-256 and license verification: [raw record](alpha3-hf-download-android.json). Separate fixtures passed cancellation, hash-failure preservation, selected-model recovery across manager recreation and manual filename retention.
- In-app Qwen3 long-translation warm median: **34.5 token/s**, with **208 output tokens per sample**. Raw tg128 median: **30.2 token/s**. The different prompt/context workloads prevent these two rates from being a direct overhead subtraction. [Four translation samples and three engine samples](app-speed-test-android-alpha3.json).
- Core: 33 tests passed; source contracts: 16; Android JS/bridge: 13; reader TypeScript check passed. Android checks cover 12 regression/catalog/download cases, three speed/lifecycle cases and three opt-in model smoke runs. The first catalog UI run had an ambiguous close-button test locator; fixing the locator and rerunning both catalog cases passed. No model failures were relabeled as format passes.
- The production [host JNI regression](alpha3-host-regression.json) passed all four cases, including queue/cache and cancellation followed by inference. Android assembly, lint, signature, ZIP/ELF 16 KiB alignment and distribution content/notices checks passed.

## Reproduction

Fetch verified source dependencies first; model weights must already exist under `artifacts/models` with the names above.

```sh
PARAREAD_CPU_THREADS=6 scripts/gradle.sh -PhostLlama=true \
  -PcmakePath=/absolute/path/to/cmake :tools:host-llama:run \
  --args='matrix docs/reports/model-matrix-local.json'
```

`-Pkleidiai=OFF` disables Arm kernels for a comparison; it does not restore the historical grammar-first sampler. `PARAREAD_MATRIX_MODEL=Qwen3-1.7` filters the matrix. Android smoke tests accept `modelPath` and `modelLabel` instrumentation arguments; live Hugging Face download testing opts in with `hfLive=true`. Neither downloads nor this integration matrix run in normal CI unit tests.
