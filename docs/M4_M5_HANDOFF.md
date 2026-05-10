# M4 → M5 Handoff Note

**From:** M4 Phase F (2026-05-10)
**To:** M5 — Memory subsystem (WS-9 retrieval, WS-10 extraction, WS-11 UI, WS-12 storage hardening)
**Status:** Ready

This is the operational handoff for the pre-flight classifier integration
that landed in M4. M5's memory subsystem reuses the same on-device
classifier — same `.tflite`, same engine, same forward pass — for the
post-turn extraction job. No second model load. The PRD §3.2.4 spec
remains the source of truth for memory product behaviour; this note
focuses on the integration seams M4 set up.

---

## 1. Reuse the M4 ClassifierEngine — don't load a second model

The shipped `.tflite` (`preflight_memory_shared_v1.0.0_int8.tflite`,
67.7 MB) emits **all three task heads in one forward pass**:

```kotlin
data class ClassifierOutput(
    val preflightLogits: FloatArray,   // [3] — M4 consumes
    val presenceLogits: FloatArray,    // [2] — M5 consumes
    val categoryLogits: FloatArray,    // [6] — M5 consumes
)
```

The Phase B engine resolves the per-head index by parsing the trailing
`:N` of `tensor.name()` (Play Services LiteRT and ai-edge-litert both
permute interpreter indices away from the name suffix order — see
CLAUDE.md inv. #12). The engine returns the full `ClassifierOutput`
regardless of which head the caller cares about. M4 reads `preflightLogits`;
M5 reads `presenceLogits` and `categoryLogits`.

**Inject the existing engine, don't construct a second one:**

```kotlin
// commonMain — what M5's MemoryExtractor looks like
class MemoryExtractor(
    private val engine: ClassifierEngine,        // singleton, M4's
    private val tokenizer: WordPieceTokenizer,   // singleton, M4's
    /* … memory store, embedder, etc. */
) {
    suspend fun extract(userMessage: String, assistantResponse: String): MemoryCandidate? {
        // Pair-encode user + assistant per training spec — see §2 below.
        val tokenized = tokenizer.encodePair(userMessage, assistantResponse)
        val output = engine.classify(tokenized.inputIds, tokenized.attentionMask)
            ?: return null  // engine unavailable → graceful no-op (PRD §3.2.4)

        val presence = softmax(output.presenceLogits)
        val pHas = presence[ClassifierOutput.PRESENCE_INDEX_HAS_EXTRACTION]
        if (argMax(output.presenceLogits) == ClassifierOutput.PRESENCE_INDEX_NO_EXTRACTION) {
            return null
        }
        val categoryProbs = sigmoid(output.categoryLogits) // multi-label, threshold 0.5
        // … build candidate(s) per active category, dedup against store, persist
    }
}
```

`softmax` / `sigmoid` / `argMax` already live in
`:shared/commonMain/classifier/internal/Softmax.kt`.

`engine.warmUp()` is already kicked off by `ChatViewModel.init`. M5's
post-turn extraction runs after the engine is loaded, so just await
`engine.isLoaded` (or call `warmUp()` again — it's idempotent under the
internal `Mutex`).

---

## 2. Memory input formatting — use `WordPieceTokenizer.encodePair`

Per `docs/M3_M4_HANDOFF.md` §3, memory training-time tokenization is:

```
[CLS] <user_message tokens> [SEP] <assistant_response tokens> [SEP] [PAD]…
```

with `truncation_strategy = only_first` — when total length exceeds 128,
tokens are dropped from the **tail of `user_message`** so the assistant
response is preserved intact. The Kotlin tokenizer already implements this
in `WordPieceTokenizer.encodePair(textA, textB)`. The pair-encoder fixture
is in `tokenizer_canonical_inputs.json` (`pair_short`,
`pair_truncate_first`); both pass byte-exact in
`WordPieceTokenizerFixtureTest`.

**Don't call `encodeSingle` and concatenate** — that produces the wrong
sub-word boundaries at the join and the model degrades silently. Use the
pair encoder.

---

## 3. Output ordering — already documented, don't re-derive

| Head | Shape | Order |
|---|---|---|
| `presenceLogits` | `[2]` | `[no_extraction, has_extraction]` |
| `categoryLogits` | `[6]` (multi-label sigmoid) | `[personal_identity, preference, professional, interest, relationship, temporary_context]` |

Constants are in `ClassifierOutput.PRESENCE_INDEX_*` (preflight has them
too; memory was deferred). Add `CATEGORY_INDEX_*` constants in `M5` if
you want symbolic indexing.

The `MemoryCategory` enum referenced by the PRD already lives in
`classifier_training/src/classifier_training/datasets/schemas.py` —
mirror it as a Kotlin enum in `:shared/commonMain/memory/` if you don't
already have one.

---

## 4. v1.0 model card numbers M5 should know

From the model card § Evaluation summary, memory regression-split metrics:

| Metric | Value | PRD §7 target | Status |
|---|---:|---:|---|
| Presence precision | **96.2%** | ≥90% | ✓ |
| Presence recall | 76.5% | — | informational |
| Presence F1 | 85.2% | — | informational |
| Category macro-F1 | 0.432 | — | known weakness #5 |
| Forget command accuracy | **100%** | — | ✓ |
| Remember command accuracy | 91.3% | — | ✓ |

Memory presence is the cleanest head — argmax on `presenceLogits`
gives a 96% precise extraction signal on real user/assistant turns.
Category multi-label is weaker (0.43 macro-F1), which the model card
flags as v1.x improvement #4 (long-tail categories under-represented:
relationship 6.4%, temporary_context 5.9%). M5 should ship with the
classifier as-is; v1.x dataset expansion closes the gap post-launch.

---

## 5. Gotchas surfaced in M4 that affect M5

1. **`com.google.ai.edge.litert:litert:2.1.4`, NOT classic TFLite or
   Play Services TFLite.** See CLAUDE.md inv. #18. The engine seam
   handles this; don't construct a second `org.tensorflow.lite.*`
   interpreter yourself.
2. **CPU-only on Pixel 7.** The GPU delegate refuses the graph
   (`BROADCAST_TO`, `EMBEDDING_LOOKUP`, `CAST INT64→FLOAT32`). p95 is
   113 ms per forward pass. M5 extraction runs **after** the user has
   their response (background coroutine), so an extra forward pass per
   turn is invisible to perceived latency — but don't run it on the main
   thread (CLAUDE.md inv. #1).
3. **Latency budget is informational.** PRD §2.3 has 80 ms aspiration
   that M4 misses by 33 ms. Memory extraction inherits the same gap.
   v1.x int32 input re-export (model card §v1.x #5) recovers both heads.
4. **Memory category density skew (multi 22.6% vs §3.2 12% target).**
   Model card known weakness #4. The classifier is biased toward
   multi-extraction predictions. M5 should expect higher-than-target
   multi-category activations on real data; the dedup pass via embedding
   similarity (cosine > 0.85, PRD §3.2.4) is the canonical fix.

---

## 6. Out-of-scope for M5 (per PRD §3.2.4)

- Gemma-generated memory text — v1 uses templated extraction
  ("User's favorite NFL team is the Eagles" from the
  `personal_identity` head + span heuristic). v1.x replaces this with
  a brief background Gemma inference call.
- Server-side sync of memories. Memories never leave the device in
  v1, even via opt-in telemetry (PRD §4.4).

---

## 7. M4 deliverables M5 inherits

| Path | Purpose |
|---|---|
| `:shared/commonMain/classifier/ClassifierEngine.kt` | Interface |
| `:shared/commonMain/classifier/ClassifierOutput.kt` | All 3 logit arrays per forward pass |
| `:shared/commonMain/classifier/WordPieceTokenizer.kt` | `encodePair(textA, textB)` for memory inputs |
| `:shared/commonMain/classifier/internal/Softmax.kt` | Numerically-stable softmax/sigmoid/argMax |
| `:shared/androidMain/classifier/LiteRtClassifierEngine.kt` | The actual; reuse via Hilt |
| `:androidApp/src/main/kotlin/.../app/di/ClassifierModule.kt` | Singleton providers |
| `models/preflight_memory_shared_v1.0.0_int8.tflite` | Already bundled in assets |
| `classifier-training/.../ci/regression_check.py` | Gates new `.tflite` updates against v1.0 baseline |

For the next classifier major version, `ct-regression-check` should run
green on the new ckpt before any `.tflite` lands in `models/`.
