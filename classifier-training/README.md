# classifier-training

Python project for constructing the two on-device classifier datasets and fine-tuning the resulting models. See `CLASSIFIER_DATASETS.md` at the repo root for the dataset specification and `PHASE1_PLAN.md` workstreams WS-5/6/7.

## Layout

```
src/classifier_training/
  datasets/         Pydantic schemas, JSONL validator, ct-stats distribution dashboard
  generation/       Synthetic generation pipeline (Ollama default, Claude optional)
  review/           Local Textual TUI for solo synthetic review (replaces Argilla)
  training/         (M3 phases E–G) fine-tuning, INT8 quantization, LiteRT conversion
prompts/            Jinja2 prompt templates for synthetic generation
scripts/            One-off scripts (seed generation, dataset stats)
tests/              pytest schema + validator tests
```

## Setup

```bash
python -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"

# Optional, only when running training:
pip install -e ".[training]"
```

## CLIs

```bash
ct-validate datasets/preflight/preflight_v0.1.0.jsonl    # schema check + summary
ct-stats    datasets/preflight/preflight_v0.1.0.jsonl    # distribution vs §2.2 targets
ct-generate-preflight --count 8 --out preflight.jsonl    # synthetic batch (Ollama)
ct-generate-memory    --count 8 --out memory.jsonl
ct-review preflight.jsonl                                # local accept/reject TUI
```

## Generation backend

Default backend is local Ollama. Override via env:

```bash
export CT_GEN_BACKEND=ollama          # default
export OLLAMA_MODEL=qwen3.5:9b        # default
export OLLAMA_HOST=http://localhost:11434

# Optional Claude fallback (requires the [claude] extra):
export CT_GEN_BACKEND=claude
export ANTHROPIC_API_KEY=...
pip install -e ".[claude]"
```

## Pipeline (M3 — see `docs/M3_PLAN.md`)

1. **Phase A** — pipeline + review tool stood up (this README). Smoke test on 50 examples each.
2. **Phase B** — synthetic seed: ~10k preflight + ~6k memory via stratified `ct-generate-*` batches, reviewed via `ct-review`, deduped, distribution tracked via `ct-stats`.
3. **Phase C** — adversarial pair authoring: ≥800 preflight pairs + ≥400 memory hard cases.
4. **Phase D** — versioning + frozen regression splits, SHA-256 committed.
5. **Phase E–G** — `training/` package fine-tunes a shared DistilBERT/MobileBERT encoder with two task heads, quantizes to INT8 LiteRT, benchmarks on Pixel 7.

## Privacy

Per CLASSIFIER_DATASETS.md §4.3, real user data only enters datasets via opt-in telemetry post-launch and is regex-prefiltered for PII. Memory extraction examples are synthetic-only — never sourced from real users.
