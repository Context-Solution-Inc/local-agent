# M3 GATE: FAIL (2 metrics short)

## Failures

- preflight: NO threshold in [0.5, 0.95] achieves 95% precision
- preflight time-sensitive recall 0.868 < 0.9

**Checkpoint:** `eval/runs/phaseF_full_20260509_162556/best.pt`
**Datasets:** preflight=`datasets/preflight/preflight_v1.0.0.jsonl`, memory=`datasets/memory/memory_v1.0.0.jsonl`
**Splits evaluated:** test, regression

## Latency (host proxy — Phase G replaces with Pixel 7)

- p50: 1.95 ms · p95: 2.00 ms · p99: 2.01 ms · mean: 1.96 ms · (200 iters, target p95 < 80.0ms)

## Pre-flight — test (983 examples)

- accuracy: 0.800  ·  macro-F1: 0.692  ·  weighted-F1: 0.800
- three-band: high=210 (precision 0.886 ✗)  middle=333  low=440
- time-sensitive recall (per-class argmax) 0.868 ✗    ·  high-band-only recall: 0.500
- adversarial pair accuracy: 0.837 (50 unique pair_ids)
- **No threshold in [0.50, 0.95] clears 95% precision** ✗

#### Threshold sweep (probability cutoff for fire-pre-flight)

| threshold | fired | fired % | precision | high-band recall |
|---:|---:|---:|---:|---:|
| 0.50 | 410 | 41.7% | 0.759 | 0.836 |
| 0.60 | 361 | 36.7% | 0.787 | 0.763 |
| 0.70 | 309 | 31.4% | 0.809 | 0.672 |
| 0.75 | 282 | 28.7% | 0.823 | 0.624 |
| 0.80 | 256 | 26.0% | 0.832 | 0.573 |
| 0.85 | 210 | 21.4% | 0.886 | 0.500 |
| 0.90 | 157 | 16.0% | 0.898 | 0.379 |
| 0.92 | 127 | 12.9% | 0.898 | 0.306 |
| 0.95 | 74 | 7.5% | 0.905 | 0.180 |


### Per-class

| Class | P | R | F1 | Support |
|---|---:|---:|---:|---:|
| search_required | 0.746 | 0.868 | 0.802 | 372 |
| search_not_required | 0.957 | 0.861 | 0.906 | 488 |
| ambiguous | 0.387 | 0.350 | 0.368 | 123 |

### Confusion matrix (rows=true, cols=pred)

| | search_required | search_not_required | ambiguous |
|---|---:|---:|---:|
| search_required | 323 | 7 | 42 |
| search_not_required | 42 | 420 | 26 |
| ambiguous | 68 | 12 | 43 |

### Per-category accuracy

| Category | Accuracy |
|---|---:|
| ambiguous | 0.350 |
| coding_math | 1.000 |
| creative | 1.000 |
| general_knowledge | 0.833 |
| markets_current | 0.870 |
| meta | 0.621 |
| news_current | 0.758 |
| opinion_reasoning | 0.886 |
| personal_memory | 0.758 |
| prices_products | 0.935 |
| schedules_events | 1.000 |
| settled_history | 0.855 |
| sports_recent | 0.920 |
| sports_upcoming | 0.786 |
| status_recent | 0.917 |
| weather | 0.897 |

## Pre-flight — regression (699 examples)

- accuracy: 0.821  ·  macro-F1: 0.742  ·  weighted-F1: 0.828
- three-band: high=163 (precision 0.908 ✗)  middle=218  low=318
- time-sensitive recall (per-class argmax) 0.853 ✗    ·  high-band-only recall: 0.532
- adversarial pair accuracy: 0.880 (79 unique pair_ids)
- **No threshold in [0.50, 0.95] clears 95% precision** ✗

#### Threshold sweep (probability cutoff for fire-pre-flight)

| threshold | fired | fired % | precision | high-band recall |
|---:|---:|---:|---:|---:|
| 0.50 | 281 | 40.2% | 0.833 | 0.842 |
| 0.60 | 249 | 35.6% | 0.859 | 0.770 |
| 0.70 | 220 | 31.5% | 0.886 | 0.701 |
| 0.75 | 203 | 29.0% | 0.892 | 0.651 |
| 0.80 | 181 | 25.9% | 0.901 | 0.586 |
| 0.85 | 163 | 23.3% | 0.908 | 0.532 |
| 0.90 | 122 | 17.5% | 0.910 | 0.399 |
| 0.92 | 93 | 13.3% | 0.914 | 0.306 |
| 0.95 | 55 | 7.9% | 0.909 | 0.180 |


### Per-class

| Class | P | R | F1 | Support |
|---|---:|---:|---:|---:|
| search_required | 0.820 | 0.853 | 0.836 | 278 |
| search_not_required | 0.942 | 0.852 | 0.895 | 345 |
| ambiguous | 0.439 | 0.566 | 0.494 | 76 |

### Confusion matrix (rows=true, cols=pred)

| | search_required | search_not_required | ambiguous |
|---|---:|---:|---:|
| search_required | 237 | 12 | 29 |
| search_not_required | 25 | 294 | 26 |
| ambiguous | 27 | 6 | 43 |

### Per-category accuracy

| Category | Accuracy |
|---|---:|
| ambiguous | 0.566 |
| coding_math | 1.000 |
| creative | 0.909 |
| general_knowledge | 0.817 |
| markets_current | 0.887 |
| meta | 0.588 |
| news_current | 0.703 |
| opinion_reasoning | 0.882 |
| personal_memory | 0.917 |
| prices_products | 1.000 |
| schedules_events | 1.000 |
| settled_history | 0.861 |
| sports_recent | 0.930 |
| sports_upcoming | 0.778 |
| status_recent | 0.846 |
| weather | 0.769 |

## Memory — test (620 examples)

- presence: precision=0.922 ✓  recall=0.768  F1=0.838  accuracy=0.852
- category macro-F1: 0.435
- explicit forget accuracy: 1.000 (n=40)
- explicit remember accuracy: 0.902 (n=51)

## Memory — regression (367 examples)

- presence: precision=0.962 ✓  recall=0.765  F1=0.852  accuracy=0.856
- category macro-F1: 0.432
- explicit forget accuracy: 1.000 (n=19)
- explicit remember accuracy: 0.913 (n=23)
