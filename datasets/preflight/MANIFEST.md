# Pre-flight Search Classifier Dataset

**Spec:** [CLASSIFIER_DATASETS.md §2](../../CLASSIFIER_DATASETS.md)
**Schema:** [`classifier-training/src/classifier_training/datasets/schemas.py::PreflightExample`](../../classifier-training/src/classifier_training/datasets/schemas.py)

## Versioning

Datasets are semantic-versioned: `preflight_vMAJOR.MINOR.PATCH.jsonl`.

- **Major** — schema-breaking change (new required field, label taxonomy change)
- **Minor** — additive content change (new examples, new categories within existing taxonomy)
- **Patch** — fixes to existing examples (label corrections, typo fixes)

The frozen `regression` split (CLASSIFIER_DATASETS.md §2.1) NEVER changes after launch.
A patch release that touches the regression split requires explicit sign-off and a
classifier re-evaluation.

## Storage

JSONL payloads are gitignored at the repo root (model artifacts and dataset content
are too large). Released versions are pushed to the team's release artifact storage
(decision pending — see `docs/M0_DECISION_MEMO.md` open question 9). Manifests,
schemas, and validators stay in git.

## Releases

| Version | Examples | Status | Cut date | Regression SHA-256 |
|---|---:|---|---|---|
| v1.0.0 | 11,670 | ✅ Frozen | 2026-05-09 | `9724f57840a4fd73ebdc318911ce7a79c6b43d3c093e314983538350521be544` |

The regression-split file `preflight_v1.0.0_regression.jsonl` is now the
**M3-locked update gate**. Any change to its content (additions, deletions,
label edits) MUST:

1. Bump the major version (e.g. `preflight_v2.0.0`).
2. Be accompanied by a fresh classifier re-evaluation against the new
   regression set.
3. Land in a single commit that updates this manifest's SHA-256.

The CI gate (M4 WS-14) will read the SHA-256 from this manifest and refuse
classifier model updates that produce different scores on the regression set.

## Files (v1.0.0)

- `preflight_v1.0.0.jsonl` — full 11,670-example dataset
- `preflight_v1.0.0_regression.jsonl` — extracted 699-row regression slice
  (sorted by `id` for deterministic hashing)

## Targets per CLASSIFIER_DATASETS.md §2.2

| Split | Count | Purpose |
|---|---|---|
| Train | 9,000 | Classifier fine-tuning |
| Val | 1,500 | Hyperparameter selection, early stopping |
| Test | 1,000 | Held-out evaluation |
| Regression | 500 | Frozen update gate (never modified post-launch) |

| Label | Proportion |
|---|---|
| `search_required` | 40% |
| `search_not_required` | 45% |
| `ambiguous` | 15% |

Adversarial pair coverage: ≥800 examples (≥7%) per CLASSIFIER_DATASETS.md §2.4.

## v1.0.0 distribution snapshot (post-dedup)

> Generated via `ct-stats datasets/preflight/preflight_v1.0.0.jsonl --markdown`.
> Re-run after any v1.x release.

## Pre-flight distribution — 11670 examples (target 12000)

### Splits

| Split | Count | Target | Status |
|---|---:|---:|---|
| train | 8536 | 9000 | −464 |
| val | 1452 | 1500 | −48 |
| test | 983 | 1000 | −17 |
| regression | 699 | 500 | ✓ +199 |

### Labels (§2.2 target proportions)

| Label | Count | Have % | Want % |
|---|---:|---:|---:|
| search_required | 4415 | 37.8% | 40% |
| search_not_required | 5903 | 50.6% | 45% |
| ambiguous | 1352 | 11.6% | 15% |

### Confidence (within decisive labels, §2.2)

| Confidence | Count | Have % | Want % |
|---|---:|---:|---:|
| high | 7902 | 76.6% | 70% |
| medium | 2268 | 22.0% | 25% |
| low | 148 | 1.4% | 5% |

### Categories

| Category | Count | % of total |
|---|---:|---:|
| sports_recent | 1036 | 8.9% |
| sports_upcoming | 167 | 1.4% |
| markets_current | 956 | 8.2% |
| weather | 355 | 3.0% |
| news_current | 920 | 7.9% |
| prices_products | 326 | 2.8% |
| status_recent | 391 | 3.4% |
| schedules_events | 264 | 2.3% |
| general_knowledge | 1646 | 14.1% |
| settled_history | 1841 | 15.8% |
| opinion_reasoning | 518 | 4.4% |
| coding_math | 874 | 7.5% |
| creative | 195 | 1.7% |
| personal_memory | 417 | 3.6% |
| meta | 412 | 3.5% |
| ambiguous | 1352 | 11.6% |

### Adversarial pairs (§2.4)

- 1139 examples across 80 pair_ids (target ≥800 examples) ✓ +339

### Naturalistic phrasings (§2.5)

- 3278 (28.1%) (target ≥30%)

### Sources

| Source | Count |
|---|---:|
| synthetic_v1 | 10531 |
| adversarial | 1139 |

## Known v1.0.0 distribution gaps

These are documented in `docs/M3_PLAN.md` Phase C status and will be revisited
based on Phase F classifier eval results before any v1.x patch:

- **Confidence "low" 1.4% vs §2.2 5% target.** qwen3.5:9b prefers confident
  labels; mitigation by `--target-confidence low` top-up batch is queued for
  Phase F if middle-band routing under-performs.
- **Naturalistic share 28.1% vs §2.5 ≥30% target** (drifted down 2pp during
  Phase C pair expansion since rephrasings skew formal). Acceptable for v1.0.0.
- **search_not_required slightly over-represented (50.6% vs 45%).** Within
  6pp; subsample at training time if it biases the model.

## Generating

```bash
cd ../../classifier-training
ct-fill preflight \
    --out ../datasets/preflight/preflight_v0.1.0.jsonl \
    --multiplier 1.0
ct-expand-pairs preflight \
    --out ../datasets/preflight/preflight_v0.1.0.jsonl \
    --variants-per-side 8
ct-dedup ../datasets/preflight/preflight_v0.1.0.jsonl --apply
```

## Validating

```bash
ct-validate ../datasets/preflight/preflight_v1.0.0.jsonl
ct-stats    ../datasets/preflight/preflight_v1.0.0.jsonl
```

## Verifying the regression freeze

```bash
sha256sum ../datasets/preflight/preflight_v1.0.0_regression.jsonl
# Must match the SHA-256 in the Releases table above.
```

## Example records

See `example.jsonl` in this directory.
