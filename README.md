# Mobile Agent — On-Device AI Assistant (Android)

Privacy-first on-device assistant running Gemma 4 E4B locally on Android, with Brave Search as the only outbound network dependency. See [PRD.md](PRD.md) for the full product spec and [PHASE1_PLAN.md](PHASE1_PLAN.md) for the implementation plan.

## Repository layout

```
.
├── PRD.md                          Product requirements
├── SYSTEM_PROMPT.md                System prompt construction spec
├── CLASSIFIER_DATASETS.md          Classifier dataset specifications
├── PHASE1_PLAN.md                  Phase 1 implementation plan
├── agent_loop_state_diagram.svg    Agent loop reference diagram
│
├── android-app/                    KMP shared module + Android Compose shell
│   ├── shared/                     commonMain (agent loop, prompt, search, storage)
│   │                               androidMain (LiteRT-LM JNI, OkHttp engine)
│   │                               iosMain (stubbed for Phase 1)
│   └── androidApp/                 Compose UI, Hilt DI, spike harness
│
├── classifier-training/            Python ML — dataset gen, fine-tuning, quantization
│   └── src/classifier_training/    Pydantic schemas, generation, labeling, training
│
├── datasets/                       JSONL files (payloads gitignored, manifests committed)
│   ├── preflight/                  Pre-flight search classifier dataset
│   └── memory/                     Memory extraction classifier dataset
│
├── eval/                           Regression harnesses, canonical query suites
│
└── docs/                           M0 decision memo, spike runbook, decision logs
```

## Targets

- **Devices:** Google Pixel 7 (Phase 1)
- **OS:** Android 16 (API 36)+
- **Language stack:** Kotlin 2.x, Jetpack Compose, Kotlin Multiplatform
- **Inference runtime:** LiteRT-LM (Android JNI)
- **Models:** Gemma 4 E4B Q4 (primary), MobileBERT-class classifiers, MiniLM-L6-v2 embedder

## Status

**Milestone 0 (Foundation & spike) — in progress.**

| Workstream | State |
|---|---|
| WS-1 LiteRT-LM Pixel 7 spike | Harness scaffolded with stub `InferenceEngine`. Awaiting device + model artifact. |
| WS-2 KMP scaffolding | In progress |
| WS-5 Pre-flight dataset (12k) | Schemas + generation prompts drafted. Argilla setup script ready. |
| WS-6 Memory extraction dataset (8k) | Schemas + generation prompts drafted. |
| Decision memo on perf envelope | Template ready in `docs/M0_DECISION_MEMO.md` — fill in after spike runs. |

## Building

The Android project lives under `android-app/`. From a machine with JDK 17 and Android SDK installed:

```bash
cd android-app
./gradlew :androidApp:assembleDebug
./gradlew :androidApp:installDebug      # requires connected Pixel 7 (or other Android 16 device)
```

The classifier-training Python project lives under `classifier-training/`:

```bash
cd classifier-training
python -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"
pytest                                  # runs schema validation tests
```

See `docs/SPIKE_RUNBOOK.md` for instructions on running the M0 inference benchmark on a Pixel 7.

## Privacy

User conversations and memories never leave the device. Only Brave Search queries (and optional opt-in classifier-improvement aggregates) generate outbound traffic. See PRD section 4.4.
