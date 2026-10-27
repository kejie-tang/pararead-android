# Additional 350M–0.6B models: format repair and translation limits

Test date: 2026-10-27. **RWKV7's 0.4B translation checkpoint is a useful new experimental candidate.** A model-specific completion prompt and strict placeholder-encoding retries recover all 18 Android emulator workloads with both Q8_0 and a local Q4_K_M requantization. NanoTranslator-365M can also recover the six host format workloads, but changes meaning too often to recommend. NanoTranslator-0.5B still loses protected content.

**These adapters are research code, not selectable providers in the released app.** Importing an RWKV file into alpha.5 will not enable this profile. The app default remains Qwen3-1.7B; Qwen3-0.6B Q4_K_M remains the available fast-preview option. Format success does not establish semantic accuracy, and none of these measurements came from the user's Dimensity 9400.

## Candidates and measured results

Sizes are decimal MB of the GGUF, not peak RAM. All tested weights and their exact source revisions, sizes, hashes and license metadata are in the [manifest](mid-small-model-manifest.json). No weights are bundled or committed.

| Candidate | File | Test path | Format result | Decision |
| --- | ---: | --- | --- | --- |
| RWKV_v7_G1d_0.4B_Translate_ctx4096_20260607 Q8_0 | 501.5 MB | Host JNI + Android JNI, experimental completion adapter | Host 6/6 basic + 12/12 additional; Android 18/18 | Most promising new candidate; awkward phrasing, omissions and numerical-direction errors remain |
| Same RWKV, local Q4_K_M | 300.7 MB | Android JNI, same adapter | 18/18 | Smaller experimental alternative; derived by requantizing the published Q8 file, not a publisher-provided Q4 |
| NanoTranslator-immersive_translate-365M Q4_K_M | 255.1 MB | Host JNI, experimental translation adapter | Plain 4/6 → repaired queue 6/6 | Format repaired, meaning unreliable |
| NanoTranslator-immersive_translate-0.5B Q4_K_M | 397.8 MB | Host JNI, experimental translation adapter | Plain 4/6 → repaired queue 5/6 | Still fails the protected batch; omissions and prompt leakage |
| Falcon-H1-0.5B-Instruct Q4_K_M | 314.8 MB | Standalone pinned llama.cpp completion tool only | Five diagnostic cases, not a production queue sweep | Bad Chinese and lost/changed markers; not added to app support |
| Qwen3-0.6B Q4_K_M, comparison | 396.7 MB | Android JNI, existing app JSON provider | 17/18 in this expanded set | Existing option; unit-contrast workload fails bounded recovery |

A workload passes when every source row commits, the production queue verifies exact protected-content inventory, every committed result contains Chinese, and reopening accepted rows makes no further provider calls. The six basic workloads contain nine source rows; the additional twelve contain twelve rows. Chinese-character presence is a research check, not a completeness or language-quality evaluation. Production JSON-mode validation does not currently enforce that extra language check; TEXT-mode recovery does.

The raw [host recovery](mid-small-host-recovery.json), [host additional texts](mid-small-host-heldout.json), [Android Q8](mid-small-android-rwkv-q8.json), [Android Q4](mid-small-android-rwkv-q4.json), and [Android Qwen comparison](mid-small-android-qwen06.json) retain attempts, failures, translations and timing. Qwen's 17/18 produces a failed research assertion; the failed case is preserved rather than discarded. Accepted rows reopened with zero model calls. The twelve additional texts were fixed after choosing the repair strategy; no prompt tuning used their outputs.

### Why these candidates

- [NanoTranslator-365M](https://huggingface.co/Mxode/NanoTranslator-immersive_translate-365M) and [NanoTranslator-0.5B](https://huggingface.co/Mxode/NanoTranslator-immersive_translate-0.5B) target translation and declare GPL-3.0. The 365M checkpoint needs local GGUF conversion; the 0.5B test uses [mradermacher's GGUF](https://huggingface.co/mradermacher/NanoTranslator-immersive_translate-0.5B-GGUF).
- The RWKV checkpoint comes from [mollysama/rwkv-mobile-models](https://huggingface.co/mollysama/rwkv-mobile-models), whose repository metadata declares Apache-2.0. Its filename's “0.4B” is the publisher's label. The source path is `gguf/RWKV_v7_G1d_0.4B_Translate_ctx4096_20260607-Q8_0.gguf`. A raw `English: …\n\nChinese:` prompt, inferred from the related [RWKV Lightning translation example](https://github.com/RWKV-Vibe/rwkv_lightning), works much better than the embedded chat template. This is not a claim that the exact checkpoint author documented that training prompt.
- [Falcon-H1-0.5B-Instruct GGUF](https://huggingface.co/tiiuae/Falcon-H1-0.5B-Instruct-GGUF) uses the custom Falcon license and a different architecture. It was tested with the pinned standalone engine, not by bypassing the app's architecture checks.
- [Interpreter-Summer-0.5B](https://huggingface.co/Ismantic/Interpreter-Summer-0.5B) uses a custom PieceTokenizer and explicitly is not an ordinary HF AutoTokenizer model. No compatible GGUF/tokenizer path was established; it was screened, not inference-tested. [SmolLM2-360M-Instruct](https://huggingface.co/HuggingFaceTB/SmolLM2-360M-Instruct) primarily targets English, so it was screened without claiming Chinese translation compatibility. The earlier [LFM350 and Qwen2.5 tests](tiny-model-recovery.md) still apply.

## What was repaired

The shared test/tool adapter is deliberately limited to **English → Simplified Chinese with an empty glossary**. It translates protected rows individually, retains IDs in code, and serializes the JSON envelope itself. It cannot yet serve the app's arbitrary languages/glossaries.

1. Use the checkpoint's translation prompt instead of asking the small model to invent ParaRead JSON. For RWKV7, request raw text completion with `English: {text}\n\nChinese:`. Its embedded chat template otherwise tended to copy or paraphrase English.
2. Start with normal `__PR0__` placeholders. On the existing queue's failed-row JSON retry, encode them as `[[PR0]]`; the last TEXT retry uses `<ph0/>`. There are no extra unbounded retries.
3. Accept the encoded result only when its complete marker inventory matches exactly, allowing word-order changes. Unknown, duplicated, missing or leading-zero IDs and mixed ASCII encoding fail. Map verified markers back to standard placeholders, then use the normal queue's restoration, validation and cache. No missing reference is appended and no position is guessed.
4. Return invalid rows as JSON null while retaining valid neighbouring rows. Cancellation propagates; native provider lifecycle and inference stay on the existing serialized executor.

Actual RWKV Q4 protected-memory example, from the Android report:

```text
Input: The model processes __PR0__ pages using __PR1__ of memory __PR2__.
First output: 该模型使用内存中的__PR1__进行__PR2__页面的处理。
```

This drops `__PR0__`, so it is rejected. Retrying only that row:

```text
Input: The model processes [[PR0]] pages using [[PR1]] of memory [[PR2]].
Output: 该模型使用[[PR1]]内存[[PR2]]处理[[PR0]]页面。
Restored: 该模型使用4 GB内存[12]处理100页面。
```

The number, memory unit and citation survive. The Chinese remains awkward, especially the citation's attachment. This is a format recovery, not an editorial correction.

### Remaining meaning errors

- RWKV Q8 and Q4 render “latency improved from 120 ms to 80 ms” as “延迟从120 ms提高到80 ms”. Values survive but “提高” expresses the wrong direction for latency. Both Android variants omit the second sentence of the quotes/newline fixture, “Keep the meaning of both sentences.” The host Q8 run retained it. The Q4 document-count sentence also omits a natural quantifier (“只有3的50文件失败”).
- NanoTranslator-365M turns “a memory-efficient method for translating scientific documents” into “一种能有效地存储和存放科学文献的存储方法”, changing translation into storage. It badly distorts document counts and drops the paragraph's final content. Passing 6/6 is insufficient.
- NanoTranslator-0.5B still drops protected memory/citation markers after both alternate encodings; it also omits text and can emit a trailing `Translated Text:` label. See both [365M plain](mid-small-nano365-plain.json) and [0.5B plain](mid-small-nano500-plain.json) reports.
- Falcon's smoke outputs confuse scientific translation with unrelated terms and drop numerical content. CLI exit code 0 means the process completed, not that format or meaning passed. The [chat-template CLI outputs](mid-small-cli-chat-templates.json) and [RWKV language-label ablation](mid-small-cli-language-labels.json) are separate from JNI queue results. The CLI's displayed `[end of text]` suffix is tool output.

## Speed: raw tokens versus completing a translation

Android API 36 ARM64 emulator, Apple M1 Pro host, eight virtual CPUs, 6 GB virtual RAM; all providers CPU-only, **six inference threads**, context4096, batch512/ubatch128, pinned llama.cpp `2280f0fd163b03d4331d49e08460f66502e35854`. No physical tablet was available. Three pp512→tg128 samples per model, medians:

| Model | pp512 tokens/s | tg128 tokens/s | 158-word paragraph queue completion |
| --- | ---: | ---: | ---: |
| RWKV 0.4B Q8_0 | 968.7 | 92.1 | 3.878 s |
| RWKV 0.4B local Q4_K_M | 319.6 | 101.2 | 3.823 s |
| Qwen3-0.6B Q4_K_M | 252.1 | 72.7 | 3.551 s |

**Do not read this as a 27–39% translation speedup.** Tokenizers differ, and RWKV generated more tokens for the same paragraph. Qwen finished that paragraph sooner despite its lower tg128. Q4 reduced file size; its prompt processing was much slower than Q8 in this run. A [repeat through the public probe script](mid-small-android-rwkv-q4-repeat.json) again passed 18/18, but its tg128 median was 91.7 rather than 101.2 tokens/s, illustrating run-to-run variation. Queue times are single observations, not stable latency benchmarks; first-case times include model loading. Repeated real-device translation timing and quality review are needed before changing recommendations.

## NanoTranslator-365M conversion and tokenizer fix

The pinned model has Qwen2 layers but a custom 8,000-token BPE vocabulary. The standard converter rejects its unrecognized tokenizer fingerprint. [The conversion wrapper](../../scripts/convert_nanotranslator_365m.py) verifies the four exact HF source hashes before selecting the existing Qwen2 pre-tokenization rule. It does not modify the vendored engine or guess a rule for arbitrary tokenizers.

The HF tokenizer also normalizes NFC. Raw native tokenization matched HF in **20/21** fixtures, with a decomposed-accent mismatch. NFC-normalizing the protected model input before native tokenization gives **21/21** exact token-ID matches, including Chinese, numbers, whitespace, emoji, markers and a complete translation prompt. Both the [failing raw comparison](mid-small-token-parity-raw.json) and [passing NFC comparison](mid-small-token-parity-nfc.json) are retained. The model's poor translation after this fix is therefore not explained by that known tokenizer discrepancy. The test corpus is finite and does not prove equivalence for all strings.

Use the pinned full llama.cpp source and its converter dependencies (Python 3.11+):

```sh
python scripts/convert_nanotranslator_365m.py --llama-dir /path/to/llama.cpp \
  --hf-dir /path/to/pinned-nano365 --outfile artifacts/models/NanoTranslator-365M-F16.gguf
/path/to/llama-quantize artifacts/models/NanoTranslator-365M-F16.gguf \
  artifacts/models/NanoTranslator-365M-Q4_K_M.gguf Q4_K_M 6
python scripts/check_nanotranslator_tokenizer.py --hf-dir /path/to/pinned-nano365 \
  --model artifacts/models/NanoTranslator-365M-Q4_K_M.gguf \
  --llama-tokenize /path/to/llama-tokenize --output artifacts/token-parity.json
```

The RWKV Q4 file was made with the same pinned quantizer:

```sh
/path/to/llama-quantize --allow-requantize \
  artifacts/models/RWKV_v7_G1d_0.4B_Translate_ctx4096_20260607-Q8_0.gguf \
  artifacts/models/RWKV_v7_G1d_0.4B_Translate-Q4_K_M.gguf Q4_K_M 6
```

This derives Q4 from already quantized Q8 weights; it is not equivalent to quantizing the original training precision. Its parent hash and resulting hash are recorded in the manifest.

## Reproduction and verification

Use the manifest to download the exact files into `artifacts/models/`; the locally converted files require the steps above. Select only the entries needed for a run. Falcon's `standaloneCliOnly` entry is not accepted by the JNI provider. The host recovery report uses the first three manifest entries; RWKV additional-text tests use only its Q8 entry.

```sh
PARAREAD_HARDWARE='Apple M1 Pro' scripts/gradle.sh -PhostLlama=true \
  -PcmakePath=/path/to/cmake :tools:host-llama:test :tools:host-llama:run \
  --args='recovery artifacts/recovery.json artifacts/selected-manifest.json user-only'
# Replace user-only with user-only-heldout for the twelve additional workloads.
scripts/gradle.sh :app:assembleDebug :app:assembleDebugAndroidTest
# Install both APKs on the chosen ARM64 test device, then:
python3 scripts/probe_small_models_android.py artifacts/selected-manifest.json \
  --adb /path/to/adb --serial DEVICE_SERIAL --output-dir artifacts/model-probes
```

For the Android Qwen comparison use its existing [manifest entry](tiny-model-recovery-manifest.json) with `researchAdapter: false`. Android research tests skip without explicit model/spec arguments. The helper is included only in host tools and `androidTest`, never app main. The native `rawCompletion` switch defaults false and only permits RWKV7 TEXT requests with an empty system prompt; ordinary app architecture selection is unchanged.

Validation: four marker/profile tests, 42 core tests, 16 source-contract tests, debug APK/test APK builds, Android lint, and four real default-provider host JNI cases including cancellation and cache reopen. The Android research sweeps additionally exercise real weights, production queue restoration and cache reopen. See the [verification record](mid-small-verification.json) and [default JNI regression](mid-small-host-regression.json). No new APK or release is associated with this research change.
