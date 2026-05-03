# Memory Extraction Classifier Dataset

**Spec:** [CLASSIFIER_DATASETS.md §3](../../CLASSIFIER_DATASETS.md)
**Schema:** [`classifier-training/src/classifier_training/datasets/schemas.py::MemoryExtractionExample`](../../classifier-training/src/classifier_training/datasets/schemas.py)

## Versioning

Same scheme as the pre-flight dataset: `memory_vMAJOR.MINOR.PATCH.jsonl`.

## Storage

JSONL payloads gitignored; manifests + schemas + validators in git. Released
versions pushed to release artifact storage.

## Current state

| Version | Examples | Status | Notes |
|---|---|---|---|
| v0.1.0 | 0 (target: 8,000) | In progress (M3) | Synthetic seed generation begins week 4 |

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
- Coverage of: implicit vs explicit preferences, temporary vs stable, sensitive facts

## Privacy

Per CLASSIFIER_DATASETS.md §4.3: memory extraction examples are SYNTHETIC ONLY.
The surrounding conversational context is too rich to anonymize safely, so we
never source memory examples from real user data — even with telemetry opt-in.

## Generating

```bash
cd ../../classifier-training
ANTHROPIC_API_KEY=... ct-generate-memory \
    --count 30 \
    --target-density empty \
    --out ../datasets/memory/memory_v0.1.0.jsonl
```

## Validating

```bash
ct-validate ../datasets/memory/memory_v0.1.0.jsonl
```
