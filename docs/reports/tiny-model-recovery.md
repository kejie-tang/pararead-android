# Can models below 0.6B recover ParaRead's output format?

Test date: 2026-10-27. **LFM2.5-350M's format failures can be avoided in a host-side prototype, but translation errors still prevent recommending it for scientific reading. Haidass-143M is only partially repaired.** Qwen3-0.6B Q4_K_M remains the fast-preview recommendation; the released app's default remains Qwen3-1.7B.

These are CPU-only, six-thread tests on an Apple M1 Pro, using the app's pinned llama.cpp JNI engine. They are **not measurements on a Dimensity 9400**. Format checks do not certify translation accuracy. The specialized adapter below is a research tool and **is not enabled in the released Android app**. No new APK release is associated with this report.

Follow-up: [additional 350M–0.6B candidates and Android repair tests](mid-small-models.md) cover RWKV 0.4B, NanoTranslator and Falcon. RWKV is a promising experimental alternative, with explicit semantic limitations.

## Files and results

Sizes are decimal MB of the exact GGUF, not peak memory. Revisions, SHA-256 and licenses are in the [baseline manifest](tiny-model-recovery-manifest.json) and the [adapter manifest with explicit runtime settings](tiny-model-recovery-adapter-manifest.json).

| Model / exact quantization | File | Released alpha.5 queue on host | Experimental user-only adapter on host | Interpretation |
| --- | ---: | --- | --- | --- |
| Qwen3-0.6B Q4_K_M | 396.7 MB | 6/6 | not rerun with this adapter | Existing fast-preview baseline |
| Qwen2.5-0.5B Q4_K_M | 491.4 MB | 6/6 | 4/6 | Fewer parameters, larger download; the adapter makes this model worse |
| LFM2.5-350M Q4_K_M | 229.3 MB | 4/6 | 6/6 | Format improves; omissions and reversed numerical relations remain |
| Haidass-Translate-143M Q8_0 | 188.2 MB | Both recorded workloads failed; sweep stopped | 4/6, with F32 KV + Flash Attention off + greedy decoding | Corrupt output is recoverable; exact protected-content handling is still unreliable |

Each full sweep has six workloads: short text, two protected sentences, two numeric contrasts, two negation sentences, quotes/newlines, and a 158-word paragraph. A workload passes only when every expected sentence commits, protected markers match exactly, the report finds Chinese in each translation, and reopening accepted sentences makes zero provider calls. No semantic-quality score is implied.

The [baseline raw report](tiny-model-recovery-baseline.json) preserves all three completed model sweeps. Haidass completed two failed workloads before that slow corrupt-output run was stopped; the remaining four are **untested**, not additional failures or passes. The escaped-text LFM baseline is also a useful limitation: its JSON was valid and the production queue committed English `reading assistant`, but the research report's additional Chinese-text check correctly marked the workload failed. JSON-mode production validation does not currently enforce that language check; TEXT-mode recovery does.

## What the adapter changes

The [complete experimental queue report](tiny-model-recovery-user-only.json) uses the real production `TranslationQueue`, cache keys, protected-content restoration and bounded retries. A host-tool wrapper changes the model-facing request:

1. Split a batch into individual protected texts. The program retains sentence IDs and constructs the JSON envelope itself.
2. Ask the model only `Translate the following text from English to Simplified Chinese.` followed by that text. Omit the empty system turn. Disable JSON grammar for these text generations; retain the Qwen3 closed empty-thinking prefill where required by the architecture.
3. Return generated text under the original ID using a JSON serializer. The normal queue still rejects missing, duplicated or changed markers and preserves independently valid neighbours.

This wrapper is explicitly limited to **English → Simplified Chinese with an empty glossary**. It does not interpret arbitrary user glossary or language settings and is not installed as an app provider. It does not append missing references, guess placeholder positions, or turn literal numbers into a claimed translation.

LFM350 passed all six workloads with **nine native generations** for the nine source rows, without a retry. The six provider requests therefore do not mean six model generations. All accepted rows reopened from cache without further inference. Qwen2.5 and Haidass still failed protected and numeric workloads despite bounded retries; their valid neighbours survived. The [direct plaintext probe](tiny-model-recovery-plain.json) is separate: Haidass passes 5/6 there because a combined two-sentence input can retain markers that it drops in an individually translated sentence. That 5/6 is **not** its queue result.

## Actual repairs and remaining errors

Source:

```text
The model processes 100 pages using 4 GB of memory [12].
```

LFM350's successful user-only output:

```text
模型处理__PR0__页面使用__PR1__的内存__PR2__。
```

The unchanged exact-marker validator restores:

```text
模型处理100页面使用4 GB的内存[12]。
```

The wording is awkward, but all protected content survives. No marker normalization or relaxed validator is used.

A more serious error survives **every format check**:

```text
Source: Accuracy fell from 91.2% to 87.6%, whereas latency improved from 120 ms to 80 ms.
LFM350: 准确度从91.2%提高到87.6%，而延迟从120 ms提高到80 ms。
```

The accuracy **fell**, but the model says **increased**. Correctly retaining all four numbers does not repair that meaning. Its short output also drops memory efficiency, its paragraph translates “engine performance” as “发射性能”, and its quoted-text output replaces the second source sentence with an explanation. Qwen2.5 and Haidass likewise omit details or distort terms. These fixed fixtures are exploratory observations, not a standardized translation benchmark.

## Haidass precision investigation

The [GGUF publisher's instructions](https://huggingface.co/umeiko/Haidass-Translate-143M-GGUF) warn about FP16 overflow and prescribe the user-only prompt, a closed empty thinking block and greedy decoding. The [original model](https://huggingface.co/DALabCommunity/Haidass-Translate-143M) is a specialized Chinese/English translator, rather than a general instruction model.

The earlier [default-runtime plaintext probe](tiny-translator-plain-probe.json) failed all four Haidass fixtures with malformed UTF-8. Repeating the same four requests and original sampler with **F32 KV storage and Flash Attention disabled together** produced readable Chinese: [raw diagnostic](tiny-model-recovery-haidass-f32-initial.json). Its protected-content case still failed by repeating/misassigning markers. These two runtime changes were combined; this experiment does **not** isolate which change caused the improvement, nor establish that every computation uses FP32.

The final user-only/greedy probe removes the empty system turn to match the publisher's example, but the queue still drops `__PR1__` in the memory sentence and loses numeric markers in the document-count sentence. F32 KV also increases runtime memory beyond the 188 MB weight file. This is a partial runtime repair, not app compatibility or evidence that 143M is a good paper translator.

A separate [literal-content experiment](tiny-model-recovery-literal.json), before the user-only template correction, supplied actual numbers, equations and URLs instead of markers. It produced more natural-looking text, but changed protected spans such as `4 GB` to `4GB` and `$E=mc^2$` to `E=mc²`. Those exact-span failures remain failures and were not used to justify weakening the app's checks.

## Reproduction and integration status

Download the manifest files into `artifacts/models/`; the tools check each file's size and SHA-256. With the verified pinned engine sources available:

```sh
PARAREAD_HARDWARE='Apple M1 Pro' scripts/gradle.sh -PhostLlama=true \
  -PcmakePath=/absolute/path/to/cmake :tools:host-llama:run \
  --args='recovery .cache/local-tiny-queue.json docs/reports/tiny-model-recovery-adapter-manifest.json user-only'

PARAREAD_HARDWARE='Apple M1 Pro' scripts/gradle.sh -PhostLlama=true \
  -PcmakePath=/absolute/path/to/cmake :tools:host-llama:run \
  --args='plain .cache/local-tiny-plain.json docs/reports/tiny-model-recovery-adapter-manifest.json'
```

Omit `user-only` to run the ordinary JSON-first queue; use the baseline manifest for default runtime settings. The original baseline Haidass sweep was interrupted as described above; do not assume rerunning a full sweep will finish quickly. A successful tool exit means report generation completed, not that every model passed.

The committed code adds explicit diagnostic F32-KV/Flash-Attention and greedy controls to `LlamaCppProvider`, and omits the system turn when its string is empty. Ordinary app prompts have a nonempty system message; app constructors retain their previous runtime defaults. No automatic model-name matching, UI model recommendation, or default download changes were introduced.

LFM uses its custom [LFM1.0 license](https://huggingface.co/LiquidAI/LFM2.5-350M); Qwen2.5 and the tested Haidass distribution declare Apache-2.0. No weights are committed or bundled. See the [verification record](tiny-model-recovery-verification.json) for build and default-provider regressions; experimental adapter results remain host-only.
