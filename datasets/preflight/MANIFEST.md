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

## Current state

| Version | Examples | Status | Notes |
|---|---|---|---|
| v0.1.0 | 0 (target: 12,000) | In progress (M3) | Synthetic seed generation begins week 4 |

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

## Generating

```bash
cd ../../classifier-training
ANTHROPIC_API_KEY=... ct-generate-preflight \
    --count 50 \
    --target-label search_required \
    --target-category sports_recent \
    --out ../datasets/preflight/preflight_v0.1.0.jsonl
```

## Validating

```bash
ct-validate ../datasets/preflight/preflight_v0.1.0.jsonl
```

## Example records

See `example.jsonl` in this directory.
