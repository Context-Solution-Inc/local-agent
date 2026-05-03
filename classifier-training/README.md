# classifier-training

Python project for constructing the two on-device classifier datasets and fine-tuning the resulting models. See `CLASSIFIER_DATASETS.md` at the repo root for the dataset specification and `PHASE1_PLAN.md` workstreams WS-5/6/7.

## Layout

```
src/classifier_training/
  datasets/         Pydantic schemas + JSONL validators (canonical types)
  generation/       Frontier-model synthetic generation pipeline + CLIs
  labeling/         Argilla integration (workspace setup, JSONL import/export)
  training/         (M3) fine-tuning, INT8 quantization, LiteRT conversion
prompts/            Jinja2 prompt templates for synthetic generation
scripts/            One-off scripts (seed generation, dataset stats)
tests/              pytest schema + validator tests
```

## Setup

```bash
python -m venv .venv && source .venv/bin/activate
pip install -e ".[dev,labeling]"

# Optional, only when running training:
pip install -e ".[training]"
```

## CLIs

```bash
ct-validate datasets/preflight/preflight_v0.1.0.jsonl    # JSONL schema check
ct-generate-preflight --count 100 --out preflight.jsonl  # synthetic batch
ct-generate-memory --count 100 --out memory.jsonl
ct-argilla-init --workspace mobile-agent                 # creates Argilla datasets
```

## Pipeline (M0–M3)

1. **Synthetic seed (M0–M1):** Generate ~10k preflight + ~6k memory examples via `ct-generate-*`, calling Claude (or another frontier model) with the templates in `prompts/`.
2. **Argilla import (M1):** `ct-argilla-init` creates the labeling workspace; raw JSONL is imported with full schema as the labeling task. Two-labeler agreement tracked per CLASSIFIER_DATASETS.md §2.7.
3. **Adversarial authoring (M2):** In-house labelers add ~2k preflight pair examples + ~2k memory hard cases.
4. **Export + version (M2):** Argilla → cleaned JSONL with `split` assignments → committed as a versioned release artifact.
5. **Training (M3):** `training/` package (training extras) fine-tunes MobileBERT/DistilBERT/MiniLM and quantizes to INT8 LiteRT. Benchmarks measured on a Pixel 7.

## Privacy

Per CLASSIFIER_DATASETS.md §4.3, real user data only enters datasets via opt-in telemetry post-launch and is regex-prefiltered for PII. Memory extraction examples are synthetic-only — never sourced from real users.
