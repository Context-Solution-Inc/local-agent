# M0 Inference Spike — Runbook

**Audience:** the engineer running the WS-1 benchmark on a Pixel 7
**Goal:** capture clean, reproducible measurements of Gemma 4 E4B Q4 inference on
Pixel 7 + Android 16. Output feeds `docs/M0_DECISION_MEMO.md`.

---

## Prerequisites

- Pixel 7 (non-Pro, non-a) running Android 16 stable or current beta
- USB cable + adb installed on dev machine
- JDK 17, Android SDK with platform 36, build-tools matching AGP 8.7.x
- Brave dev key not required for this spike (no network involved)
- Access to LiteRT-LM Android AAR (Maven coordinates or local AAR file)
- Gemma 4 E4B Q4 model artifact (`.litertmodel` or equivalent) — ~2.8 GB

---

## Stage 1 — Validate the harness with the stub engine

The point: confirm the spike harness, metrics collection, thermal monitor, and
result persistence all work end-to-end before swapping in the real engine. If
something is broken in the harness it's much easier to debug against a stub.

```bash
cd android-app
./gradlew :androidApp:installDebug
adb shell am start -n com.contextsolutions.mobileagent.debug/com.contextsolutions.mobileagent.app.spike.SpikeActivity
```

In the SpikeActivity UI, tap **Run benchmark**. The stub will:

- Simulate a 6 s cold load
- Emit canned tokens at ~8 tok/s
- Run all 5 canonical prompts
- Write `filesDir/spike-results/spike-<runId>.json`

Pull the result:

```bash
adb shell run-as com.contextsolutions.mobileagent.debug ls files/spike-results/
adb shell run-as com.contextsolutions.mobileagent.debug cat files/spike-results/spike-<runId>.json > stub-run.json
```

Confirm:

- All 5 prompts ran
- `firstTokenLatencyMs` ≈ 4,000 (matches stub config)
- `sustainedTokensPerSecond` ≈ 8.0
- `peakRssBytes` is non-zero
- `thermalStateMaxObserved` ≥ 0

If any of these is missing or implausible, fix the harness before moving on.

---

## Stage 2 — Wire in real LiteRT-LM

1. Add the LiteRT-LM AAR to `android-app/shared/build.gradle.kts` (androidMain dependencies)
   and to `gradle/libs.versions.toml`. Pin a specific version.
2. Create a real `LiteRtInferenceEngine` in
   `android-app/shared/src/androidMain/kotlin/com/contextsolutions/mobileagent/inference/`
   that implements the `InferenceEngine` interface. Reference patterns:
   - `loadModel`: open the model file, allocate KV cache, choose the requested accelerator
   - `generate`: invoke LiteRT generation and emit `GenerationEvent.TokenChunk` per decoded token
   - `unload`: release the LiteRT session
3. Update `InferenceModule` (in `androidApp/.../di/`) to bind `InferenceEngine` to
   `LiteRtInferenceEngine` instead of `StubInferenceEngine`.

Keep the stub binding behind a build flavor or a `BuildConfig.USE_STUB_ENGINE`
flag — useful for the rest of M1 development when you don't want to wait on
real model load.

---

## Stage 3 — Get the model on device

Push the Gemma 4 E4B Q4 artifact to the app's external files dir (NOT the apk
assets — apks have a 200 MB cap):

```bash
adb push gemma-4-e4b-q4.litertmodel \
    /sdcard/Android/data/com.contextsolutions.mobileagent.debug/files/models/
```

In `SpikeActivity.kt` set `MODEL_PATH_PLACEHOLDER` to the on-device path:

```kotlin
private const val MODEL_PATH_PLACEHOLDER =
    "/sdcard/Android/data/com.contextsolutions.mobileagent.debug/files/models/gemma-4-e4b-q4.litertmodel"
```

(M1 replaces this with the WorkManager-driven download flow per PRD §3.5.)

---

## Stage 4 — Run the real benchmark

Run THREE times back-to-back to capture cold load, warm load, and thermal
trajectory:

1. Cold: launch app, immediately tap Run. Captures cold model load time.
2. Warm: tap Run again 30 s later. Measures sustained perf without cold-load tax.
3. Sustained: leave app open, tap Run a third time after 60 s. By this run the
   device has been generating for 5+ minutes total — captures thermal envelope.

Conditions to control:

- **Plugged in vs battery:** run BOTH. Battery results are what users actually experience.
- **Screen on:** always on for these runs (screen-off would pause the activity).
- **Ambient:** record room temp. Hot environment will throttle thermals faster.
- **Other apps:** force-close everything else first (`adb shell am kill-all`).

Pull each run JSON. Three files per condition.

---

## Stage 5 — Try each accelerator path

Re-run the benchmark with `InferenceConfig.accelerator` set to each of `NPU`, `GPU`,
`CPU` (assuming the LiteRT-LM AAR exposes these). For each, capture:

- Whether the runtime accepts the accelerator request (logs)
- First-token p50, sustained tok/s
- Peak RSS, peak native heap
- Thermal trajectory

The accelerator decision in `M0_DECISION_MEMO.md` is driven by these results.

---

## Stage 6 — Optional: switch to Gemma 4 E2B Q4

If E4B numbers fail the Phase 1 envelope, repeat stages 3–5 with the E2B Q4
artifact. Compare side-by-side and fill in Decision 1 of the memo.

---

## Pulling all results off-device

```bash
mkdir -p ~/spike-results
adb shell run-as com.contextsolutions.mobileagent.debug \
    ls files/spike-results/ | tr -d '\r' | while read f; do
        adb shell run-as com.contextsolutions.mobileagent.debug \
            cat "files/spike-results/$f" > ~/spike-results/$f
    done
```

---

## Filling in the memo

Open `docs/M0_DECISION_MEMO.md` and copy the relevant numbers from the result
JSONs into each table. Make and document each of the five open decisions.

Sign off the memo and circulate. M1 doesn't start until M0 is signed.
