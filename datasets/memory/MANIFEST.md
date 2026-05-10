# Memory Extraction Classifier Dataset

**Spec:** [CLASSIFIER_DATASETS.md §3](../../CLASSIFIER_DATASETS.md)
**Schema:** [`classifier-training/src/classifier_training/datasets/schemas.py::MemoryExtractionExample`](../../classifier-training/src/classifier_training/datasets/schemas.py)

## Versioning

Same scheme as the pre-flight dataset: `memory_vMAJOR.MINOR.PATCH.jsonl`.

The frozen `regression` split (CLASSIFIER_DATASETS.md §3.2) NEVER changes
after launch. A patch release that touches the regression split requires
explicit sign-off and a classifier re-evaluation.

## Storage

JSONL payloads gitignored; manifests + schemas + validators in git. Released
versions pushed to release artifact storage.

## Releases

| Version | Examples | Status | Cut date | Regression SHA-256 |
|---|---:|---|---|---|
| v1.0.0 | 7,707 | ✅ Frozen | 2026-05-09 | `7801cb2386dd8f72fd1ffefb2b737a47988e7aac81bf322e1507db72d4c94ae6` |

The regression-split file `memory_v1.0.0_regression.jsonl` is now the
**M3-locked update gate**. Any change to its content (additions, deletions,
label edits) MUST:

1. Bump the major version (e.g. `memory_v2.0.0`).
2. Be accompanied by a fresh classifier re-evaluation.
3. Land in a single commit that updates this manifest's SHA-256.

The CI gate (M4 WS-14) will read the SHA-256 from this manifest and refuse
classifier model updates that produce different scores on the regression set.

## Files (v1.0.0)

- `memory_v1.0.0.jsonl` — full 7,707-example dataset
- `memory_v1.0.0_regression.jsonl` — extracted 367-row regression slice
  (sorted by `id` for deterministic hashing)

## Targets per CLASSIFIER_DATASETS.md §3.2

| Split | Count | Purpose |
|---|---|---|
| Train | 6,000 | Classifier fine-tuning |
| Val | 1,000 | Hyperparameter selection |
| Test | 600 | Held-out evaluation |
| Regression | 400 | Frozen update gate |

| Memory density | Proportion |
|---|---|
| 0 memories to extract | 60% (load-bearing — over-extraction is dominant failure mode) |
| 1 memory | 28% |
| 2+ memories | 12% |

Required hard-case minimums:

- ≥200 explicit-forget examples
- ≥200 explicit-remember examples
- Coverage of: implicit vs explicit preferences, temporary vs stable, sensitive

Phase C added an `pair_id` field to the schema to track adversarial hard-case
pairs (implicit_vs_explicit_preference, temporary_vs_stable, sensitive).

## Privacy

Per CLASSIFIER_DATASETS.md §4.3: memory extraction examples are SYNTHETIC ONLY.
The surrounding conversational context is too rich to anonymize safely, so we
never source memory examples from real user data — even with telemetry opt-in.

## v1.0.0 distribution snapshot (post-dedup)

> Generated via `ct-stats datasets/memory/memory_v1.0.0.jsonl --markdown`.
> Re-run after any v1.x release.

## Memory distribution — 7707 examples (target 8000)

### Splits

| Split | Count | Target | Status |
|---|---:|---:|---|
| train | 5770 | 6000 | −230 |
| val | 950 | 1000 | −50 |
| test | 620 | 600 | ✓ +20 |
| regression | 367 | 400 | −33 |

### Memory density (§3.2 target proportions)

| Density | Count | Have % | Want % |
|---|---:|---:|---:|
| empty | 3915 | 50.8% | 60% |
| one | 2049 | 26.6% | 28% |
| multi | 1743 | 22.6% | 12% |

### Memory categories (across all extracted memories)

| Category | Count | % |
|---|---:|---:|
| personal_identity | 982 | 17.1% |
| preference | 1504 | 26.3% |
| professional | 1327 | 23.2% |
| interest | 1211 | 21.1% |
| relationship | 366 | 6.4% |
| temporary_context | 339 | 5.9% |

### Hard-case coverage (§3.5)

| Field | Count | Target | Status |
|---|---:|---:|---|
| explicit forget | 527 | ≥200 | ✓ +327 |
| explicit remember | 737 | ≥200 | ✓ +537 |
| with negative_extractions | 4406 | — | 57.2% |

### Adversarial hard-case pairs (Phase C)

- 333 examples across 48 pair_ids covering implicit_vs_explicit_preference,
  temporary_vs_stable, and sensitive disclosure cases.

### Sources

| Source | Count |
|---|---:|
| synthetic_v1 | 7374 |
| adversarial | 333 |

## Known v1.0.0 distribution gaps

Documented in `docs/M3_PLAN.md` Phase B/C status; revisit after Phase F eval:

- **Density skew: multi 22.6% vs 12% target.** The strong multi exemplar
  in the prompt leaks into other density batches. Optional subsample at
  training time if it biases the classifier.
- **Empty 50.8% vs 60% target.** Same root cause; could top-up via
  `ct-fill memory --target-density empty` if Phase F eval shows
  over-extraction.
- **Regression slot under-target (367 vs 400).** Driven by the random
  split-assignment in `ct-fill`; acceptable for v1.0.0. Future top-up
  passes can force-assign new rows to regression.

## Generating

```bash
cd ../../classifier-training
ct-fill memory \
    --out ../datasets/memory/memory_v0.1.0.jsonl \
    --multiplier 1.0
ct-expand-pairs memory \
    --out ../datasets/memory/memory_v0.1.0.jsonl \
    --variants-per-side 5
ct-dedup ../datasets/memory/memory_v0.1.0.jsonl --apply
```

## Validating

```bash
ct-validate ../datasets/memory/memory_v1.0.0.jsonl
ct-stats    ../datasets/memory/memory_v1.0.0.jsonl
```

## Verifying the regression freeze

```bash
sha256sum ../datasets/memory/memory_v1.0.0_regression.jsonl
# Must match the SHA-256 in the Releases table above.
```

## Example records

See `example.jsonl` in this directory.
