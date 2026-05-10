# M3 → M4 Handoff Note

**From:** M3 Phase H (2026-05-09)
**To:** M4 / WS-8 (pre-flight classifier integration), WS-14 (eval harness CI gate)
**Status:** Ready

This is the operational handoff for the v1.0 classifier artifact. The model
card (`docs/preflight_memory_shared_v1.0.0_MODEL_CARD.md`) is the source
of truth on accuracy, footprint, and known weaknesses; this doc focuses on
what M4 needs to wire up correctly.

---

## 1. Artifact

```
models/preflight_memory_shared_v1.0.0_int8.tflite        — 67.7 MB, ship
models/preflight_memory_shared_v1.0.0.tflite             — 264.6 MB, FP32 reference
docs/preflight_memory_shared_v1.0.0_MODEL_CARD.md      — full spec
```

For Android shipping, **bundle the INT8 artifact** in
`:androidApp/src/main/assets/preflight_memory_shared_v1.0.0_int8.tflite`.
The FP32 build stays in `models/` for debugging only — do not ship it.

Alternative (if APK size becomes a concern): host the .tflite alongside the
Gemma 4 download on first launch via `WorkManager`, mirroring the pattern in
`ModelDownloadWorker`. 67.7 MB is small enough to bundle by default.

---

## 2. TFLite signature

Inputs (both `int64`, fixed shape `[1, 128]`):

| Name | Shape | Dtype | Meaning |
|---|---|---|---|
| `serving_default_args_0:0` | `[1, 128]` | `int64` | DistilBERT input_ids |
| `serving_default_args_1:0` | `[1, 128]` | `int64` | attention_mask (1 for real tokens, 0 for padding) |

Outputs (all `float32`):

| Name | Shape | Meaning |
|---|---|---|
| `StatefulPartitionedCall:0` | `[1, 3]` | preflight_logits — order: `[search_required, search_not_required, ambiguous]` |
| `StatefulPartitionedCall:1` | `[1, 2]` | presence_logits — order: `[no_extraction, has_extraction]` |
| `StatefulPartitionedCall:2` | `[1, 6]` | category_logits — order: `[personal_identity, preference, professional, interest, relationship, temporary_context]` |

The single .tflite produces all three outputs in **one forward pass** (~12 ms
host-CPU INT8). The agent picks whichever head it needs — the unused heads
cost ~1.4 M extra params (negligible).

**Apply softmax to preflight_logits and presence_logits**; sigmoid to
category_logits (multi-label, threshold 0.5 per category).

---

## 3. Tokenizer + preprocessing

The Android side **must** replicate the training-time tokenization exactly,
or the classifier degrades silently:

| | |
|---|---|
| Tokenizer | `distilbert-base-uncased` (HuggingFace) |
| Vocabulary file | `vocab.txt` from the HF repo (30,522 entries) |
| Strategy | WordPiece, lowercased input |
| Special tokens | `[CLS]` (id 101), `[SEP]` (id 102), `[PAD]` (id 0) |
| Max length | **128** (truncate longer inputs) |
| Padding | right-pad with id 0 to length 128, attention_mask matches |

For Android, the canonical path is:

1. Bundle `vocab.txt` as an asset alongside the .tflite.
2. Use either:
   - **Play Services LiteRT Support Library**'s `BertTokenizer` (if available
     in the version on Play Services), OR
   - A small in-app WordPiece tokenizer (~100 LoC) that reads `vocab.txt`
     and replicates HF's `do_lower_case=True` + `clean_up_tokenization_spaces`
     behavior.

**Preflight input formatting:**
```
[CLS] <query tokens> [SEP] [PAD]…
```

**Memory input formatting** (two-segment):
```
[CLS] <user_message tokens> [SEP] <assistant_response tokens> [SEP] [PAD]…
```
Truncation strategy is `only_first` (truncate user_message, keep assistant_response intact).

A small fixture test in `:androidApp` should tokenize a known string and
assert the resulting input_ids match what the Python tokenizer produces.
Without this fixture, drift is silent and easy to miss.

---

## 4. Routing thresholds (configurable per PRD §3.2.1)

Default thresholds for the **pre-flight classifier** routing decision:

```kotlin
// p_search_required = softmax(preflight_logits)[0]
val PRE_FLIGHT_HIGH_BAND_THRESHOLD = 0.85   // configurable via shipped JSON
val PRE_FLIGHT_LOW_BAND_THRESHOLD = 0.15

when {
    pSearchRequired > PRE_FLIGHT_HIGH_BAND_THRESHOLD -> fireSearch()    // high band
    pSearchRequired < PRE_FLIGHT_LOW_BAND_THRESHOLD  -> skipSearch()    // low band
    else                                              -> letGemmaDecide() // middle band
}
```

**Configurable via shipped JSON** per PRD §3.2.1 — surface as a settings
override, not just a build constant. Telemetry-driven post-launch tuning
is the documented path to closing the §7 precision gap (model card known
weaknesses #1, #2).

For **memory presence** classification, no calibration band:

```kotlin
val presence = softmax(presence_logits).argMax()  // 1 = has_memory, 0 = none
if (presence == 1) {
    val categories = sigmoid(category_logits).map { it > 0.5 }
    // emit one extraction per active category (span text via Gemma callback in v1.x)
}
```

---

## 5. Latency expectations

| Build | Host CPU p95 | Pixel 7 CPU estimate | Pixel 7 GPU (Mali-G710) estimate |
|---|---:|---:|---:|
| INT8 .tflite | 14.7 ms | 45-60 ms | 5-10 ms |

**WS-8 must benchmark on real Pixel 7 before integration sign-off.** The
real number replaces the host proxy in the model card. Expected operating
point: GPU delegate via Play Services LiteRT, well under the 80 ms gate.

If the GPU delegate is unavailable on a given device (older Play Services,
GrapheneOS), fall back to CPU XNNPACK — still under target.

---

## 6. Regression-set CI gate (WS-14)

The frozen regression splits are committed in the manifests with SHA-256:

| | SHA-256 |
|---|---|
| `datasets/preflight/preflight_v1.0.0_regression.jsonl` | `9724f57840a4fd73ebdc318911ce7a79c6b43d3c093e314983538350521be544` |
| `datasets/memory/memory_v1.0.0_regression.jsonl` | `7801cb2386dd8f72fd1ffefb2b737a47988e7aac81bf322e1507db72d4c94ae6` |

**WS-14 wiring:**

1. CI checks out the regression files.
2. Verifies SHA-256 matches the manifest.
3. Runs `ct-eval-classifier --ckpt <new model> --split regression …`
4. Compares the regression-split metrics against the v1.0 baseline (in
   `eval/runs/phaseF_full_20260509_162556/metrics.json`).
5. Fails the build if any v1.0-passing metric regresses by >2pp.

The regression split is **immutable** — a major version bump (v2.0.0)
is required to change it, with a fresh classifier eval. See
`datasets/preflight/MANIFEST.md` and `datasets/memory/MANIFEST.md` for
the policy.

---

## 7. Reference inference path (Python)

Use this as a reference when implementing the Kotlin equivalent. Bundle a
small unit test in the Python package that runs end-to-end against the
.tflite to lock the contract:

```python
from ai_edge_litert.interpreter import Interpreter
from transformers import AutoTokenizer
import numpy as np

tokenizer = AutoTokenizer.from_pretrained("distilbert-base-uncased")
interp = Interpreter(model_path="models/preflight_memory_shared_v1.0.0_int8.tflite")
interp.allocate_tensors()

# Pre-flight input
encoded = tokenizer(
    "did the eagles win last night",
    truncation=True, max_length=128, padding="max_length",
    return_tensors="np",
)
inputs = interp.get_input_details()
outputs = interp.get_output_details()
interp.set_tensor(inputs[0]["index"], encoded["input_ids"].astype(np.int64))
interp.set_tensor(inputs[1]["index"], encoded["attention_mask"].astype(np.int64))
interp.invoke()

# Outputs by index — confirm via outputs[i]["name"]
preflight_logits = interp.get_tensor(outputs[0]["index"])  # [1, 3]
presence_logits  = interp.get_tensor(outputs[1]["index"])  # [1, 2]
category_logits  = interp.get_tensor(outputs[2]["index"])  # [1, 6]

p_search = float(np.exp(preflight_logits[0, 0]) / np.exp(preflight_logits[0]).sum())
print(f"p(search_required) = {p_search:.3f}")  # > 0.85 → fire search
```

**Note:** the output ordering is determined by the export — confirm with
`interp.get_output_details()[i]["name"]` rather than assuming the index.
The names will be `StatefulPartitionedCall:0` (preflight),
`StatefulPartitionedCall:1` (presence), `StatefulPartitionedCall:2`
(category) for v1.0 builds.

---

## 8. v1.x improvement queue

In rough priority for telemetry-driven follow-up:

1. **Search_required ↔ ambiguous boundary** — dominant cause of the 6.4pp
   precision gap. Target with anonymized real queries from M6 telemetry.
2. **Confidence-low under-representation** (1.4% vs §2.2 5%). Affects
   middle-band routing calibration. Targeted `ct-fill --target-confidence low`
   batches.
3. **Memory density skew** (multi 22.6% vs 12% target). Subsample at
   training time, OR top-up empties via `ct-fill memory --target-density empty`.
4. **Memory category macro-F1 0.435.** Long-tail categories
   (relationship 6.4%, temporary_context 5.9%) need more data.
5. **Naturalistic-phrasing share drift** (28.1% post-Phase-C, target 30%).
   Re-tune `prompts/preflight_pair_expansion.j2` for more naturalistic variants.

All five are documented in the model card "Known weaknesses" section.

---

## 9. Open questions for M4

1. **Where is the .tflite stored at install time** — bundled in APK assets, or
   downloaded on first launch? `:androidApp` currently bundles in
   `src/main/assets/`; 67.7 MB is small enough that bundling is fine.
2. **Tokenizer implementation choice** — Play Services LiteRT Support
   Library vs custom WordPiece. Confirm Play Services support has the
   required tokenizer in the version pinned in `:shared/androidMain`.
3. **GPU delegate fallback policy** — same pattern as Gemma 4
   (`LiteRtInferenceEngine.tryInitialize`)? Surface degraded mode in UI?
4. **Threshold config surface** — JSON shipped in assets, or surfaced in
   Settings as advanced toggles? PRD §3.2.1 says configurable; UI exposure
   is a product call.

These don't block WS-8 from starting — sensible defaults exist.

---

## Reproducing v1.0 from scratch

If the .tflite needs to be rebuilt:

```bash
# 1. Train (requires datasets/preflight/preflight_v1.0.0.jsonl + memory_v1.0.0.jsonl)
ct-train-classifier \
    --preflight-jsonl datasets/preflight/preflight_v1.0.0.jsonl \
    --memory-jsonl    datasets/memory/memory_v1.0.0.jsonl \
    --output-dir      eval/runs/v1.0_repro \
    --epochs 5 --batch-size 32 --lr 2e-5 --seed 42

# 2. Export INT8
ct-export-litert \
    --ckpt eval/runs/v1.0_repro/best.pt \
    --output models/preflight_memory_shared_v1.0.0_int8.tflite \
    --max-length 128 --int8

# 3. Verify regression hashes still match the manifest
sha256sum datasets/preflight/preflight_v1.0.0_regression.jsonl
sha256sum datasets/memory/memory_v1.0.0_regression.jsonl

# 4. Re-eval
ct-eval-classifier \
    --ckpt eval/runs/v1.0_repro/best.pt \
    --preflight-jsonl datasets/preflight/preflight_v1.0.0.jsonl \
    --memory-jsonl    datasets/memory/memory_v1.0.0.jsonl \
    --output-dir eval/runs/v1.0_repro
```

Total wall-clock on RTX 5090: ~10 min.
