# M5 Kickoff Prompt

Use this verbatim in a fresh Claude Code session to start M5 (Memory subsystem).
The prompt asks Claude to read context, ask clarifying questions, and produce a
phase plan **before** implementing — same shape that worked for M3 and M4.

---

## The prompt

```
Please create an implementation plan for M5 — Memory subsystem. Ask any
clarifying questions before implementing.

**Background read first** (CLAUDE.md auto-loads, but read these explicitly):
- `PRD.md` §3.2.4 — full memory subsystem spec (store schema, embedder,
  retrieval, creation, user controls, failure modes, privacy).
- `SYSTEM_PROMPT.md` §5 — `[MEMORY CONTEXT BLOCK]` template the prompt
  assembler injects when retrieval finds relevant memories.
- `CLASSIFIER_DATASETS.md` §3 — memory dataset spec, density distribution,
  hard-case categories. Useful for understanding what the classifier was
  trained on and how false-positive rates were tuned.
- `docs/M4_M5_HANDOFF.md` — operational handoff covering classifier engine
  reuse pattern, pair-encoder API, gotchas inherited from M4.
- `docs/preflight_memory_shared_v1.0.0_MODEL_CARD.md` — memory metrics M5
  inherits (presence precision 96.2%, category macro-F1 0.43, density skew
  in v1.0 dataset).
- `PHASE1_PLAN.md` §5 M5 — workstream scope (WS-9 / WS-10 / WS-11 / WS-12).

**M4 ship state** (already merged on `main`):
- Pre-flight classifier wired into `AgentLoop` via `:shared/commonMain/classifier/PreflightRouter`.
  Engine runs on `com.google.ai.edge.litert:litert:2.1.4` (NOT classic TFLite —
  see CLAUDE.md inv. #18). Pixel 7 CPU latency p95=113 ms.
- Same `.tflite` (`preflight_memory_shared_v1.0.0_int8.tflite`, 67.7 MB)
  emits all 3 task heads in one forward pass: preflight (M4 consumes),
  presence (M5 will consume), category (M5 will consume).
- `WordPieceTokenizer.encodePair(textA, textB)` is shipped and byte-exact
  against HF `distilbert-base-uncased` — use it for memory inputs (M3
  handoff §3 mandates `[CLS] user_msg [SEP] assistant_resp [SEP]`,
  truncation `only_first`).
- `ClassifierEngine` interface returns the full `ClassifierOutput`
  regardless of which head the caller cares about. **Don't load a second
  model.** Inject the existing engine singleton via Hilt (`ClassifierModule`).

**M5 scope per PHASE1_PLAN §5:**

- **WS-9 — Memory store + retrieval.** all-MiniLM-L6-v2 INT8 embedder
  integration (separate model, ~25 MB, loaded same lazy-on-chat-screen
  pattern as the classifier). SQLite table per PRD §3.2.4 with
  embeddings as BLOBs. Brute-force cosine over up to 1,000 entries
  (sub-10 ms on Pixel 7 for 1k × 384-dim per the budget). Retrieval
  K=5 / threshold 0.5, expiration filter, recency weighting. Eviction:
  expired → 90-day-stale → LRU+frequency.
- **WS-10 — Memory extraction.** Background job after each user turn.
  Calls `engine.classify(userTurn + assistantResponse)` (same engine
  M4 uses), reads `presenceLogits` + `categoryLogits`. Templated
  candidate generation for v1 (Gemma fallback deferred to v1.x per PRD
  §3.2.4). Embedding-based dedup (cosine > 0.85). Explicit
  remember/forget command detection — same classifier, the M3 dataset
  has dedicated forget/remember labels.
- **WS-11 — Memory management UI.** Settings list grouped by category,
  edit/delete individual entries, clear all, disable creation toggle.
  Per-conversation indicator showing memories created during that
  conversation. `[MEMORY CONTEXT BLOCK]` flows through the existing
  `PromptAssembler.assembleStructured(memoryBlock = ...)` parameter
  (already wired but no-op until M5).
- **WS-12 — Storage hardening.** Verify the memory database file is
  on Android FBE Credential Encrypted Storage. Ensure no memory content
  flows to logcat, Crashlytics, or telemetry payloads.

**Things to think through in your plan:**

1. **Embedder runtime.** all-MiniLM-L6-v2 INT8 is from sentence-transformers.
   Does it work on the same `com.google.ai.edge.litert:litert` runtime the
   M4 classifier uses, or does it need its own runtime? Verify with a
   minimal spike before locking architecture (Phase A pattern from M4
   worked well to retire risks early).
2. **Embedder memory footprint.** Stays resident or lazy-load + unload?
   25 MB is small enough to keep resident; PRD §4.2 budgets 200 MB total
   for auxiliary models and we're at 67.7 + 25 = 92.7 MB. Easy.
3. **Retrieval order vs pre-flight order in the agent loop.** PRD §2.2
   says memory retrieval and pre-flight run "in parallel." The current
   `AgentLoop.run()` calls `preflightRouter.route()` synchronously. Two
   options: (a) leave pre-flight synchronous, run retrieval afterward
   (simpler, adds ~10 ms p95 to the front of high-band queries);
   (b) actually parallelize via `coroutineScope { async { ... } }`
   (~10 ms saved on parallel completion). Recommend (a) for v1 — the
   complexity isn't worth 10 ms of an already-fast path.
4. **Memory injection into pre-flight rewriting.** "did my team win"
   currently aborts to FallThrough because the rewriter has no memory
   context. Once retrieval is in, the rewriter could resolve "my team"
   to "Eagles" via the retrieved memory — the canonical PRD §3.2.1
   example. Where does this hook live? In `QueryRewriter` (passes
   retrieved memories in the constructor closure) or in a new
   `MemoryAwareRewriter` that wraps `QueryRewriter`?
5. **Extraction trigger point.** PRD §3.2.4 says extraction runs "after
   the user has received their response." `AgentLoop` emits
   `AgentEvent.Done` when generation completes; the natural hook is
   in the consumer (`ChatViewModel`) on `Done`, not inside `AgentLoop`
   itself. Keeps memory extraction from blocking generation if it fails.
6. **Templated candidate generation in v1.** PRD §3.2.4 says v1 uses
   "templated extraction with span markers." What does that look like?
   The classifier emits a category multi-label distribution; we need
   actual text. Two approaches: (a) regex-extract spans from the user
   turn matching the category (e.g., `personal_identity` →
   `\bI(?:'m|\s+am)\s+(\w+)`), or (b) just save the whole user turn
   labeled with the categories. (a) is more useful but more work and
   error-prone; (b) is degenerate but ships immediately. Recommend
   discussing.
7. **Remember/forget commands.** "remember that I'm allergic to peanuts"
   should bypass the normal extraction confidence threshold and create
   a memory directly. The M3 dataset has explicit
   `EXPLICIT_REMEMBER` / `EXPLICIT_FORGET` labels — does the classifier
   surface those, or do we keyword-match? Check the classifier output
   shape vs what's in the schema.
8. **UI surface — full-blown management screen vs minimal**. PRD §3.2.4
   says first-class user-facing feature with edit/delete/disable. Phase
   D-equivalent UI work could be substantial. Worth scoping the minimum
   shippable: list view + delete + disable-creation toggle for v1, with
   edit + per-category grouping as v1.x?

**Don't write code yet** — produce a phase plan with concrete deliverables
per phase, identify the architectural seams, and flag any open questions
that need product/UX answers. We'll align on scope before implementation.
Ask whatever clarifying questions you need.
```

---

## After M5 phase plan is agreed

The same Phase A→F pattern that worked for M4 should apply:

- **Phase A** — Spike: confirm the embedder loads on `com.google.ai.edge.litert`.
  If not, the architectural fallback is bundling the embedder as a separate
  TFLite runtime (sentence-transformers can export to .onnx → TFLite).
- **Phase B** — Embedder integration + memory store schema. SQLDelight
  schema for `memories` table (id, text, category, conversation_id,
  created_at, last_accessed_at, embedding BLOB, expires_at).
- **Phase C** — Retrieval (cosine top-K), eviction policy.
- **Phase D** — Extraction job + classifier reuse + remember/forget commands.
- **Phase E** — UI: management screen + memory-injected prompt path.
- **Phase F** — Polish + handoff to M6.

The classifier engine is already there — don't re-instantiate it.
