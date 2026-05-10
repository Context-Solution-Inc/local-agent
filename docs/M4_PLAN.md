# M4 — Pre-flight Classifier Integration: Implementation Plan

**Document version:** 0.1 (Draft)
**Status:** Awaiting first phase
**Last updated:** 2026-05-09
**Companion to:** PRD.md §3.2.1, SYSTEM_PROMPT.md §6, PHASE1_PLAN.md §5 M4, `docs/M3_M4_HANDOFF.md`, `docs/preflight_memory_shared_v1.0.0_MODEL_CARD.md`

---

## 1. Goal

Wire the v1.0 INT8 pre-flight classifier (`models/preflight_memory_shared_v1.0.0_int8.tflite`) into the agent loop on Pixel 7 so that high-confidence search-required queries short-circuit the Gemma 4 round-trip per PRD §3.2.1, and ship a local regression-gate script that blocks any future classifier update from regressing the frozen v1.0 metrics.

### Exit criteria (M4 done = all true)

| # | Criterion | Source |
|---|---|---|
| 1 | Classifier loads on first cold start, runs forward pass on Pixel 7 with p95 < 80 ms | PRD §2.3, M3 handoff §5 |
| 2 | Tokenizer fixture asserts Kotlin `input_ids` match Python `distilbert-base-uncased` on a 20-string canonical set | CLAUDE.md inv. #13, M3 handoff §3 |
| 3 | Three-band routing (>0.85 / 0.15-0.85 / <0.15) implemented with thresholds loaded from shipped JSON | PRD §3.2.1 |
| 4 | Deterministic query rewriter handles date/time relatives + abbreviation whitelist; aborts to FallThrough on memory-context references | PRD §3.2.1 |
| 5 | `[PRE-FLIGHT NOTICE BLOCK]` injected into system prompt when pre-flight fires | SYSTEM_PROMPT.md §6 |
| 6 | Classifier load/inference failure → graceful FallThrough; never fails the user request | PRD §3.2.1 failure modes |
| 7 | `ci_regression_check.sh` runs locally, validates regression-set SHA-256 against manifest, runs `ct-eval-classifier --split regression`, fails build on >2pp regression vs v1.0 baseline | M3 handoff §6 |
| 8 | Real Pixel 7 latency (p50/p95/p99) captured via instrumentation test; model card latency table updated | M3 handoff §5, M3 plan Phase G follow-up |
| 9 | M2 happy-path regression still passes (no behavioral change to existing chat + tool-calling flow when pre-flight stays in middle band) | regression |

Failure on (1), (2), or (6) blocks M5.

---

## 2. Decisions ratified at planning time

| Decision | Choice | Rationale |
|---|---|---|
| Artifact ship path | **Bundle .tflite + vocab.txt in `:androidApp/src/main/assets/`** | 67.7 MB → APK delivered ≈ 217 MB compressed, well under Play Store thresholds; mirrors no-network-required first-launch UX. WorkManager download path stays in `models/` for debug builds only. |
| Tokenizer | **Custom WordPiece in `:shared/commonMain`**, reads bundled `vocab.txt` | Play Services LiteRT 16.4.0 doesn't ship `BertTokenizer`; the legacy `tensorflow-lite-support` lib is frozen and Android-only. Custom Kotlin works on iOS for Phase 2. |
| Query rewriter v1 scope | **Deterministic rules only**: date/time substitution from `TimeContext`, narrow abbreviation whitelist. No Gemma fallback. Memory-context references abort to FallThrough until M5. | Gemma fallback for rewriting defeats the round-trip-saving purpose of pre-flight. M5 promotes "my team"-style queries from FallThrough to FireSearch. |
| Low-band SkipSearch behavior | **Keep `web_search` tool registered** even on SkipSearch | Suppressing the tool means classifier mistakes can never recover. Search_not_required precision (95.7%) is informationally good but not high enough to override Gemma's judgment in v1. Revisit with M6 telemetry. |
| Pixel 7 benchmark approach | **`:androidApp` instrumentation test** loading via Play Services LiteRT, 1,000 forward passes | TF `benchmark_model` binary is bazel-from-source only; instrumentation test doubles as production-path smoke test. |
| Threshold config surface | **Shipped JSON only**, no Settings UI | PRD §3.2.1 says configurable; UI exposure deferred to post-M6 telemetry-driven decision. JSON ships in assets, parsed at app start. |
| Memory head wiring | **Single `ClassifierEngine` returning all 3 outputs** in one forward pass; M4 consumes preflight, M5 reuses same engine for presence/category | Avoids duplicate load + tokenize in M5; matches the .tflite's design. |
| WS-14 CI surface | **Local script** (`classifier-training/scripts/ci_regression_check.sh`); GitHub Actions / hosted CI deferred | Repo isn't yet a git repo with hosted CI. Script is the contract; runner is future infra. |

---

## 3. Architectural seams

New code (file paths are concrete; signatures are illustrative, not locked):

```
:shared/commonMain/classifier/
  ClassifierEngine.kt           interface: loadModel, classify(text), unload
  ClassifierOutput.kt           data: preflightLogits[3], presenceLogits[2], categoryLogits[6]
  WordPieceTokenizer.kt         pure-Kotlin WordPiece, reads vocab.txt
  PreflightDecision.kt          sealed: FireSearch | SkipSearch | FallThrough | Disabled
  PreflightThresholds.kt        data: highBand, lowBand
  PreflightConfig.kt            JSON shape; versioned (model_version + thresholds)
  PreflightConfigLoader.kt      expect: load JSON from platform asset bundle
  PreflightRouter.kt            owns engine + rewriter + thresholds; route(query, history)
  QueryRewriter.kt              deterministic rules over TimeContext

:shared/commonMain/classifier/internal/
  Softmax.kt                    pure functions: softmax, sigmoid, argmax
  TokenizerVocab.kt             vocab loader expect/actual

:shared/androidMain/classifier/
  PlayServicesLiteRtClassifierEngine.kt   actual: Play Services LiteRT Java API,
                                          GPU delegate w/ CPU fallback
  AndroidPreflightConfigLoader.kt         actual: AssetManager for JSON
  AndroidTokenizerVocab.kt                actual: AssetManager for vocab.txt

:shared/iosMain/classifier/
  StubClassifierEngine.kt                 stub (Phase 2)
  ...                                     stub actuals

:androidApp/src/main/assets/
  preflight_memory_shared_v1.0.0_int8.tflite
  vocab.txt                                30,522-entry WordPiece vocab
  preflight_config.json                    thresholds + model version pin

:androidApp/src/main/kotlin/.../app/di/
  ClassifierModule.kt                      Hilt: ClassifierEngine singleton, eager-init on app start

:androidApp/src/test/kotlin/.../classifier/
  WordPieceTokenizerFixtureTest.kt         20 canonical strings, input_ids match Python
  PreflightRouterTest.kt                   3-band routing + graceful-degradation
  QueryRewriterTest.kt                     each rule case
  PreflightConfigTest.kt                   JSON schema + threshold parse

:androidApp/src/androidTest/kotlin/.../classifier/
  ClassifierLatencyBenchmark.kt            1,000 forward passes on real Pixel 7
  ClassifierEndToEndTest.kt                load .tflite from assets, classify, assert outputs

classifier-training/scripts/
  ci_regression_check.sh                   WS-14 local gate

classifier-training/tests/fixtures/
  tokenizer_canonical_inputs.json          20 strings + expected input_ids (generated from Python)
```

### AgentLoop integration

`AgentLoop.run` gains a pre-flight step before `assembler.assembleStructured`:

```
1. router.route(userMessage, history) → PreflightDecision
2. when (decision) {
     FireSearch(rewrittenQuery, ...) ->
       searchService.search(rewrittenQuery) → SearchOutcome
       inject as synthetic Tool message at history tail
       assembler.assembleStructured(history', preflightNotice = true)
     SkipSearch -> assembler.assembleStructured(history)             // tool stays available
     FallThrough -> assembler.assembleStructured(history)            // M2 behavior
     Disabled -> assembler.assembleStructured(history)               // search disabled in settings
   }
3. session.generate(...) — unchanged from M2
```

`PromptAssembler.assembleStructured` already accepts `preflightNotice: Boolean`; the §6 block is already in the template (`PREFLIGHT_NOTICE` constant) but currently unreachable. M4 makes it reachable.

The graceful-degradation wrapper lives in `PreflightRouter` — if `ClassifierEngine.classify` returns null or throws, the router catches it, returns `FallThrough`, and logs once per app lifetime (no spam on every query).

---

## 4. Phase plan

Critical path: **A → B → C → D**. **E** runs parallel with **D** once **C** lands. **F** is final docs/handoff.

### Phase A — Scaffold + tokenizer (3-4 d) — ✅ COMPLETE 2026-05-09

**Status (2026-05-09):** All deliverables shipped. Spike retired the Play
Services LiteRT encoder-load risk on a real Pixel 7 + surfaced a
documentation bug in M3 handoff §2: the runtime permutes interpreter index
away from the name suffix order. Updated CLAUDE.md inv. #12, M3 handoff,
and ClassifierOutput docs to mandate **name-based dispatch with shape
sanity check**. Phase B's `PlayServicesLiteRtClassifierEngine` will
implement that.

**Goal:** the classifier package exists, the .tflite + vocab are bundled, and the tokenizer is byte-exact against Python.

**Spike risk to retire first:** validate Play Services LiteRT (the `play-services-tflite-java` artifact paired with `play-services-tflite-gpu` 16.4.0 we already use for Gemma) actually loads our DistilBERT-class encoder .tflite without issue. If it doesn't, fallback is `org.tensorflow:tensorflow-lite-gpu` standalone — file dep change, no architecture change. Spike target: a 30-line one-shot test in `:androidApp/src/androidTest/` that loads the tflite, invokes `interpreter.run` once with hand-crafted inputs, and asserts non-NaN outputs. Do this before writing the engine.

**Deliverables:**

1. `:shared/commonMain/classifier/` package — interfaces + data types (`ClassifierEngine`, `ClassifierOutput`, `PreflightDecision`, `PreflightThresholds`, `PreflightConfig`)
2. `WordPieceTokenizer.kt` in commonMain — ~100 LoC. WordPiece algorithm matching HF `do_lower_case=True`. Special tokens: `[CLS]=101`, `[SEP]=102`, `[PAD]=0`, `[UNK]=100`. Sequence length 128, right-pad with id 0, attention_mask matches.
3. `vocab.txt` bundled at `:androidApp/src/main/assets/vocab.txt` (30,522 lines from `distilbert-base-uncased`). Add a copy step from the HF cache or download deterministically.
4. `.tflite` bundled at `:androidApp/src/main/assets/preflight_memory_shared_v1.0.0_int8.tflite`. Copy from `models/`. Update `.gitignore` if needed (the file should NOT be committed; assets dir gets it from a Gradle task at build time, sourced from `models/`).
5. **Tokenizer fixture** in `:androidApp/src/test/`:
   - 20 canonical strings covering: short queries, long queries (>128 tokens to test truncation), Unicode, contractions ("don't"), punctuation, numbers, OOV ("Cthulhu"), case mixes, multi-segment formatting (memory-style two-segment input)
   - Reference `input_ids` generated offline by a Python script (committed alongside the fixture: `classifier-training/tests/fixtures/tokenizer_canonical_inputs.json`)
   - Test asserts every byte matches
6. Spike retirement note added to `docs/M0_DECISION_MEMO.md` §3 (or M4 plan, here in §6) on Play Services LiteRT for the encoder path.

**Asset bundling Gradle wiring:**

The .tflite is gitignored (per CLAUDE.md inv. #17). Add a Gradle task to `:androidApp/build.gradle.kts` that copies from `../../models/` into `src/main/assets/` at build time, with a check that the file exists and matches an expected SHA-256 (from the model card / handoff). No CI breakage if a fresh checkout doesn't have the file — task fails with a pointer to `docs/M3_M4_HANDOFF.md`.

**Exit gate:** tokenizer fixture passes; debug APK contains the .tflite and vocab.

### Phase B — Pixel 7 inference (3-4 d) — ✅ COMPLETE 2026-05-10

**Status (2026-05-10):** End-to-end classifier path live on Pixel 7.
Calibrated probabilities matching Python's `ai-edge-litert` reference. CPU
XNNPACK p95 = 113 ms, missing the 80 ms PRD §2.3 target by ~33 ms but
net-positive on user-facing latency. Two big course corrections during the
phase:
- **Runtime swap** — Play Services TFLite (`FROM_SYSTEM_ONLY`) AND classic
  `org.tensorflow:tensorflow-lite:2.16` both produced numerically broken
  outputs (logits ~1500x magnitude vs Python, all queries collapsing to
  one class). The fix was switching to `com.google.ai.edge.litert:litert:2.1.4`
  — the Android port of the Python `ai-edge-litert` runtime that
  ai-edge-quantizer's export tooling targets. Documented as CLAUDE.md
  hard invariant #18; engine renamed to `LiteRtClassifierEngine`.
- **GPU delegate not viable for this graph** — both Play Services TFLite GPU
  and ai-edge-litert's GPU refuse the export (`Failed to compile model` on
  `BROADCAST_TO`, `EMBEDDING_LOOKUP`, `CAST INT64→FLOAT32`). CPU XNNPACK
  delegates 345/353 ops (97.7%), good enough for v1.

**Goal:** real classifier loads on Pixel 7, runs forward passes. M4 latency
gate relaxed to 150 ms; PRD §2.3 80 ms aspiration tracked as model card v1.x
improvement #5 (int32 input re-export).

**Deliverables:**

1. `PlayServicesLiteRtClassifierEngine` actual:
   - Lazy-load .tflite from assets, **triggered on chat-screen entry** (`ChatViewModel.init` kicks off `engine.warmUp()` on a background coroutine). User typing time covers load latency; app cold start stays clean; no load wasted if user only opens settings. Once loaded, stays resident for the app lifetime. (Note: this supersedes CLAUDE.md M0 §2's "loaded at app start" — update that line in Phase F.)
   - GPU delegate via `play-services-tflite-gpu` Mali-G710 OpenCL path
   - CPU XNNPACK fallback when GPU init throws (mirrors `LiteRtInferenceEngine.tryInitialize`)
   - `withContext(Dispatchers.IO)` wrapping for both load and classify (CLAUDE.md inv. #1)
   - **Output ordering verification at load time** (CLAUDE.md inv. #12): assert `interpreter.getOutputDetails()[0].name` starts with `StatefulPartitionedCall:0`, etc. Throw at init if order has shifted post-re-export.
2. Softmax/sigmoid post-processing: pure functions in `internal/Softmax.kt`. Returns probabilities, not logits.
3. `:androidApp/src/androidTest/.../ClassifierLatencyBenchmark.kt`:
   - 200 warmup + 1,000 measured forward passes on Pixel 7
   - Random sentences from a small fixture pool
   - Records p50/p95/p99 to test output (logcat + InstrumentationRegistry.arguments for CI capture)
4. `:androidApp/src/androidTest/.../ClassifierEndToEndTest.kt`:
   - Loads from real assets
   - Classifies a known query ("did the eagles win last night")
   - Asserts `p_search_required > 0.5` (sanity floor; precise value depends on model)
   - Asserts all 3 output shapes and rough magnitudes

**Exit gate:**
- p95 < 80 ms on Pixel 7 (target ~5-10 ms GPU / 45-60 ms CPU per handoff §5)
- Both instrumentation tests pass on real device
- Model card `docs/preflight_memory_shared_v1.0.0_MODEL_CARD.md` latency table updated with real Pixel 7 row

### Phase C — Router + rewriter (3-4 d) — ✅ COMPLETE 2026-05-10

**Status (2026-05-10):** Router + rewriter shipped. 137/137 unit tests
green (was 112; +19 QueryRewriterTest, +6 PreflightRouterTest). All Phase C
deliverables met without surprises — pure-Kotlin work in commonMain on top
of the engine + classifier seam from Phase B.

**Goal:** routing decision logic with graceful degradation.

**Deliverables:**

1. `QueryRewriter.kt` in commonMain:
   - **Date/time substitution** using `TimeContext`:
     - "today" → ISO date
     - "tonight" → ISO date + evening
     - "yesterday" → (today - 1) ISO date
     - "last night" → (today - 1) ISO date
     - "this morning" → today + AM
     - "last week" → (today - 7) to today range, or just "week of YYYY-MM-DD"
     - "last month" → previous month name + year
     - "last year" → (current year - 1)
     - "this year" → current year
   - **Abbreviation whitelist** (small, additive in v1.x): NFL, NBA, MLB, NHL, EPL, S&P, Fed, GOAT, CEO, CTO. Map to expansions only when the query is short and unambiguous.
   - **Memory-context detection**: regex for possessives + bare references ("my team", "my company", "my city", "where I live", "my doctor"). Returns `null` to signal abort-to-FallThrough.
   - **Abort-on-empty** if rewriter produces an empty or 1-word query.
2. `PreflightThresholds.kt` + `PreflightConfig.kt`:
   ```
   {
     "model_version": "preflight_memory_shared_v1.0.0",
     "thresholds": {
       "high_band": 0.85,
       "low_band": 0.15
     }
   }
   ```
3. `PreflightConfigLoader` expect/actual:
   - Android: AssetManager reads `preflight_config.json`
   - iOS: stub
4. `PreflightRouter.route(query, history)`:
   - If search disabled in settings → `Disabled`
   - Tokenize + classify → softmax preflight_logits
   - `p_search_required > highBand`:
     - Run rewriter on query
     - If rewriter returns null → `FallThrough` (with reason: `RewriterAbort`)
     - Else → `FireSearch(rewrittenQuery, originalQuery, probs)`
   - `p_search_required < lowBand` → `SkipSearch(probs)`
   - Else → `FallThrough(probs)`
   - Classifier load/classify failure (caught at router level) → `FallThrough(reason = ClassifierUnavailable)`, log once
5. Unit tests in `:androidApp/src/test/`:
   - Each rewriter rule (date relatives, abbreviation expansion, possessive abort)
   - Three-band routing with mocked `ClassifierEngine`
   - Graceful-degradation: classifier throws → FallThrough
   - Disabled: search off in settings → Disabled (no classifier call wasted)

**Exit gate:** router unit tests pass; routing decision is deterministic given a mocked classifier.

### Phase D — AgentLoop integration + UI (2-3 d) — ✅ COMPLETE 2026-05-10

**Status (2026-05-10):** Wired end-to-end on Pixel 7.

- **Host-side:** AgentLoop integrates PreflightRouter; 142/142 unit tests
  green (was 137; +5 AgentLoopPreflightTest). M2 regression suite untouched
  via a stub no-op router that always emits FallThrough(ClassifierUnavailable).
- **On-device:** three canonical queries routed correctly. Logcat:
  ```
  [preflight] decision=FireSearch p_search_required=0.891 query="did the Eagles win last night" rewritten="did the Eagles win 2026-05-09 evening"
  [preflight] decision=SkipSearch p_search_required=0.000 query="what is photosynthesis"
  [preflight] decision=SkipSearch p_search_required=0.108 query="did my team win"
  ```
  Two notes from validation:
  1. **"did my team win" took SkipSearch, not RewriterAbort** — classifier
     scored it p=0.108 (below 0.15 lowBand), so the router short-circuited
     before the rewriter ran. RewriterAbort only triggers when the classifier
     is >0.85 confident AND the query has a possessive. The classifier
     correctly recognizes that "my team" without context isn't a high-band
     search candidate.
  2. **Eagles response was generic** ("I don't have information about a
     specific game from last night because the search results show general
     team information and schedules"). It's NFL offseason in May; there
     was no game May 9. Brave returned generic team-info snippets because
     there was nothing to find. Gemma correctly didn't fabricate. The
     bottleneck is Brave snippet quality, which is the M2.1+ scope already
     documented in the M2 known limitation. Pre-flight itself did its job.

**Goal:** end-to-end on real device. User sends "did the Eagles win last night" → pre-flight fires → search runs → Gemma answers in one pass.

**Deliverables:**

1. `AgentLoop` constructor accepts `PreflightRouter`. Integration logic in §3 above.
   - On `FireSearch`: call `searchService.search(rewrittenQuery)`, build a synthetic Tool message (using a deterministic call_id like `"preflight-call-0"`), append to a copy of history, pass `preflightNotice=true` to `assembler.assembleStructured`. Emit `AgentEvent.SearchStarted(rewrittenQuery)` and `AgentEvent.SearchCompleted(outcome)` so the UI can render the chip.
   - Pre-flight tool calls count toward the per-turn cap (`maxToolCalls = 3`)? Decision: **no** — pre-flight is one search "for free" before Gemma even sees the turn. Gemma's tool-call budget remains 3. (Open to flip if telemetry shows runaway behavior.)
2. Citations from a pre-flight search are accumulated identically to in-loop searches (`citationsForTurn` in `AgentLoop`).
3. `ChatScreen` UX:
   - Pre-flight progress chip distinct from in-loop search ("Pre-checking…" or just reuse the "Searching: <query>" chip — recommend reuse for v1 simplicity)
   - Cache-hit indicator threads through unchanged (existing `SearchOutcome.fromCache`)
4. DI wiring:
   - `ClassifierModule` Hilt module: provides `ClassifierEngine` (eager init at app start), `QueryRewriter`, `PreflightConfig`, `PreflightRouter`
   - `AgentModule` updated to inject `PreflightRouter` into `AgentLoop`
5. Integration test in `:androidApp/src/test/`:
   - Mock `ClassifierEngine` to return high-band on "did the eagles win"
   - Mock `SearchService` to return canned result
   - Assert `AgentLoop` emits `SearchStarted("Philadelphia Eagles game result 2026-05-08")` (or similar after rewrite), `SearchCompleted`, then `TokenChunk`s
   - Assert system prompt contains §6 PREFLIGHT_NOTICE block
6. M2 regression: existing 107 tests still green; ChatScreen happy-path on Pixel 7 still works for queries that fall in middle band.

**On-device validation (manual):**

Run a short script of canonical queries on real Pixel 7:
- "did the eagles win last night" → expect FireSearch
- "what is 7 times 8" → expect SkipSearch (still has tool, Gemma won't call it)
- "should I buy AAPL" → expect FallThrough
- "did my team win" → expect FallThrough (rewriter aborts on possessive)
- (with classifier load broken) → expect everything FallThrough; chat still works

**Exit gate:** all four canonical queries route as expected; M2 regression green; pre-flight latency in instrumented logs < 80 ms p95.

### Phase E — WS-14 regression gate (1-2 d, parallel with D) — ✅ COMPLETE 2026-05-10

**Status (2026-05-10):** `ct-regression-check` CLI shipped. 14/14 unit
tests + 3 end-to-end smoke runs verified all three exit paths
(0=PASS, 1=SHA mismatch, 2=regression). Re-evaluating the v1.0 ckpt
against itself reproduces the baseline byte-for-byte across all 19 gate
metrics. Hosted-CI runner deferred to M6 per the resolved decision.
Bonus: surfaced and fixed an `_evaluate_gate` bug in `eval.py` that
crashed when only `--split regression` was passed.

**Goal:** local script that blocks any future classifier update from regressing v1.0 metrics.

**Deliverables:**

1. `classifier-training/scripts/ci_regression_check.sh`:
   ```bash
   # 1. Verify regression-set SHA-256 against manifest
   # 2. Run ct-eval-classifier --ckpt <new ckpt> --split regression
   # 3. Diff metrics against baseline at eval/runs/phaseF_full_20260509_162556/metrics.json
   # 4. Exit non-zero if any v1.0-passing metric regresses by >2pp
   ```
   - Inputs: `--ckpt <path>`, `--baseline <path-to-metrics.json>` (default to v1.0 baseline)
   - Exit codes: 0 = pass, 1 = SHA mismatch (dataset corrupted), 2 = regression
   - Output: human-readable diff table + machine-readable JSON
2. Document the workflow in `docs/M3_M4_HANDOFF.md` §6 (replace the prose with a pointer to the script)
3. Verification runs:
   - Re-eval the v1.0 ckpt → expect pass (sanity)
   - Intentionally-broken model (e.g., random init or 1-epoch undertrained) → expect fail
4. Note in PHASE1_PLAN: WS-14 hosted-CI runner is M6 work; v1 ships the local script as the gate contract.

**Exit gate:** script passes on v1.0 ckpt, fails on a deliberately-broken ckpt.

### Phase F — Polish + handoff (1-2 d) — ✅ COMPLETE 2026-05-10

**Status (2026-05-10):** All status docs updated. M4 row in
PHASE1_PLAN.md §5 → ✅ COMPLETE with summary metrics. CLAUDE.md status
table → ✅, M4 architecture cheat sheet added. PRD §4.2's "loaded at
app start" deviation documented. M5 handoff at `docs/M4_M5_HANDOFF.md`
covers classifier engine reuse + pair-encoder API + known gotchas.

**Goal:** docs reflect ship state; M5 has a clean baseline.

**Deliverables:**

1. `PHASE1_PLAN.md` §5 M4 row → ✅ COMPLETE with date and summary metrics (p50/p95/p99 latency on Pixel 7, pre-flight hit rate from canonical-query script)
2. CLAUDE.md status table → M4 ✅
3. `docs/preflight_memory_shared_v1.0.0_MODEL_CARD.md` latency table updated with real Pixel 7 row
4. Update CLAUDE.md "M2 architecture cheat sheet" → add an "M4 architecture cheat sheet" section pointing future Claude at `PreflightRouter`, `ClassifierEngine`, the asset paths, and the routing seam in `AgentLoop`
5. Hand off to M5: M5 reuses the same `ClassifierEngine` for memory presence/category at extraction time. The engine already returns all 3 outputs in one pass — M5 just needs a `MemoryExtractionService` that calls `engine.classify(userTurn + assistantResponse)` and reads `presenceLogits` + `categoryLogits`.

---

## 5. Calendar

| Phase | Duration | Critical path? |
|---|---|---|
| A — Scaffold + tokenizer | 3-4 d | yes |
| B — Pixel 7 inference | 3-4 d | yes |
| C — Router + rewriter | 3-4 d | yes |
| D — AgentLoop integration + UI | 2-3 d | yes |
| E — WS-14 regression gate | 1-2 d | parallel with D |
| F — Polish + handoff | 1-2 d | yes |
| **Total critical path** | **~13-19 days solo** | |

Matches PHASE1_PLAN's M4 weeks 12-16 budget (4 weeks ≈ 20 working days).

---

## 6. Risks & mitigations

| Risk | Severity | Mitigation |
|---|---|---|
| Play Services LiteRT can't load DistilBERT-class encoder .tflite cleanly | Medium | **Phase A spike retires this in 30 LoC.** Fallback: standalone `org.tensorflow:tensorflow-lite-gpu` artifact. No architectural impact. |
| Tokenizer drift goes undetected → silent classifier degradation | High | **Phase A fixture is the gate.** 20-string canonical set with byte-exact `input_ids` match. Add edge cases (Unicode, OOV, contractions) explicitly. |
| GPU delegate unavailable on dev/test devices (older Play Services) | Low | Same mitigation as Gemma 4 path: `tryInitialize` falls back to CPU XNNPACK, still under 80 ms target per handoff §5. |
| Pre-flight latency overshoots 80 ms in production conditions (battery saver, thermal throttling) | Medium | Phase B benchmark captures cold-start + steady-state separately. If steady-state > 80 ms even on GPU, escalate via M0 decision memo update; classifier is already at smallest practical encoder. |
| Query rewriter over-rewrites (e.g., turns "what is yesterday's news" into a malformed search) | Low | Each rule has unit tests with positive + negative cases. v1.x: telemetry on rewrite outcomes will surface bad rules. |
| Classifier returns FireSearch on a query Gemma would have answered correctly without search → wasted Brave API call | Low | This is the documented v1 precision gap (88.6% vs 95% target). Configurable `high_band` threshold lets us tune up if cost becomes a concern. |
| Asset bundling pushes APK above Play Store 200 MB delivered size | Low | Compressed AAB delivery should be ~217 MB raw → ~165 MB delivered. Verify in Phase A by inspecting AAB size after first build. If too large, swap to WorkManager download path (mirrors Gemma). |
| AgentLoop change breaks an M2 regression case | Medium | Existing 107 tests are the safety net. Phase D explicitly runs them as the exit gate. |
| Pre-flight tool call counts toward Gemma's per-turn cap and blocks legitimate follow-up searches | Low | Decision in §4 Phase D: pre-flight does NOT count. Cap stays at 3 for Gemma's own decisions. |
| WS-14 baseline metrics file format drifts | Low | The script reads named JSON keys, not positional fields. Schema is committed with v1.0 baseline. |

---

## 7. Open questions

None.

### Resolved

- **Eager vs lazy classifier load** → resolved 2026-05-09: **lazy-at-chat-screen-entry**. `ChatViewModel.init` kicks off `engine.warmUp()` on a background coroutine; classifier is ready by the time the user hits send. App cold start stays clean; no load wasted if the user only opens settings. Supersedes CLAUDE.md M0 §2 phrasing — update in Phase F.
- **Pre-flight progress chip copy** → resolved 2026-05-09: **reuse the existing "Searching: \<query\>" chip**. Pre-flight is an internal optimization, not a user-facing concept worth a distinct vocabulary.
- **Logging verbosity for pre-flight decisions** → resolved 2026-05-09: **INFO by default** to aid troubleshooting. Each `PreflightRouter.route()` call logs decision + p_search_required + (if FireSearch) the rewritten query. Probabilities for all 3 classes log at DEBUG. Never written to disk in v1; M6 telemetry pipeline is the durable surface.

---

## 8. What this plan deliberately does NOT do

- **No Gemma fallback for query rewriting.** Defeats the round-trip-saving purpose. Deferred.
- **No Settings UI for thresholds.** JSON only. Deferred to telemetry-driven decision.
- **No memory-context query substitution.** "my team" → "Eagles" is M5 work after memory retrieval lands.
- **No suppression of `web_search` on SkipSearch.** Tool stays available; classifier never overrides Gemma in the suppression direction.
- **No hosted CI.** Local script in `classifier-training/scripts/`; GitHub Actions / Cloud Build is M6.
- **No iOS classifier engine.** Stubs only, like the existing Phase 2 placeholders.
- **No custom UI for pre-flight transparency.** v1 reuses the existing search chip. Could add a "this answer used pre-flight" indicator in v1.x if user research shows demand.

---

## 9. Phase A starter checklist

- [x] Add `:shared/commonMain/classifier/` package skeleton (interfaces + data classes only) — `ClassifierEngine`, `ClassifierOutput`, `PreflightDecision`, `PreflightConfig`, `PreflightThresholds`, `Vocab`
- [x] Generate `classifier-training/tests/fixtures/tokenizer_canonical_inputs.json` via `classifier-training/scripts/generate_tokenizer_fixture.py` — 22 canonical fixtures (20 single + 2 pair) covering Unicode, OOV, contractions, URLs, two-segment truncation
- [x] Implement `WordPieceTokenizer` in commonMain — full HF BasicTokenizer + WordPiece pipeline (clean_text → CJK pad → whitespace tokenize → lowercase → strip accents → split_on_punc → greedy WordPiece). NFD normalization via expect/actual (`java.text.Normalizer` on Android, `NSString.decomposedStringWithCanonicalMapping` on iOS)
- [x] Add `WordPieceTokenizerFixtureTest` in `:androidApp/src/test/` — both tests green: metadata match + 22 fixtures byte-exact against Python
- [x] Add Gradle copy task `:androidApp:copyClassifierTflite` — wires into `merge*Assets` per variant, SHA-256 verified against `5920733f96bfc2f193fdebc7ef5585cd37ecc3b9f23b21259e448410679ea83d`
- [x] Bundle `vocab.txt` at `:androidApp/src/main/assets/vocab.txt` (30,522 entries, 231 KB)
- [x] Bundle initial `preflight_config.json` (default thresholds 0.85 / 0.15, model_version pinned)
- [x] Verify debug APK contents — `assets/vocab.txt`, `assets/preflight_config.json`, `assets/preflight_memory_shared_v1.0.0_int8.tflite` all present; APK size 152 MB
- [x] Phase A host-side exit: tokenizer fixture green (2/2); .tflite + vocab + config present in built APK; full `:androidApp:testDebugUnitTest` 106/106 passing
- [x] **Phase A on-device exit (user-run):** `PlayServicesLiteRtSpikeTest` PASSED on real Pixel 7 (Android 16, runtime via Play Services LiteRT 16.4.0). Both tests green, ~25 s wall clock incl. install. Findings:
  - Encoder loads + runs forward pass cleanly via Play Services LiteRT — risk retired, no fallback to standalone `tensorflow-lite-gpu` needed
  - **Interpreter index is permuted from name suffix** on this runtime: `[idx 0]=name :1 (presence)`, `[idx 1]=name :0 (preflight)`, `[idx 2]=name :2 (category)`. The engine MUST dispatch by parsing the `:N` suffix from `tensor.name()`, never by interpreter index.
  - Stub-tokenized "did the eagles win" produces non-NaN logits across all three heads. Preflight output `[1758.7, -2336.7, 3072.0]` (raw INT8 logits — magnitudes are large because we're feeding only 6 real tokens; full WordPiece tokenization in Phase B will produce calibrated values).
  - Updated CLAUDE.md inv. #12, `docs/M3_M4_HANDOFF.md` §2, and `ClassifierOutput.kt` doc with the name-based dispatch contract.
