"""Training infrastructure for M3 Phase E/F.

Modules:
  data       — torch Datasets, label encoders, multi-task collator
  model      — SharedEncoderTwoHeads (DistilBERT base + 3 task heads)
  losses     — multi-task weighted loss
  train      — ct-train-classifier CLI
  eval       — ct-eval-classifier CLI (§7 metrics report + PASS/FAIL gate)
  quantize   — (Phase G) INT8 dynamic quantization
  export_litert — (Phase G) ai-edge-torch → .tflite
  benchmark_pixel7 — (Phase G) on-device latency harness

Heavy deps (torch, transformers) live behind the `[training]` extra; importing
this package without them raises a helpful ImportError.
"""
