"""WS-14 local regression gate.

Verifies a candidate classifier checkpoint against the v1.0 baseline using the
frozen regression splits as ground truth. Run before any new ``.tflite`` lands
in ``models/``:

    ct-regression-check --ckpt path/to/best.pt

Exit codes
----------
- ``0`` — pass: every gate metric is within ``--threshold-pp`` of the baseline.
- ``1`` — SHA-256 mismatch on one of the regression JSONL files. The frozen
  regression set was tampered with; refuse to evaluate further.
- ``2`` — regression: one or more gate metrics dropped by more than
  ``--threshold-pp`` versus the baseline. Inspect the printed diff table.
- ``3`` — infrastructure error (missing baseline file, ``ct-eval-classifier``
  failed, etc.). Logged with a stack trace.

The script is the contract for v1; the M6 hosted-CI runner will wrap this
exact command. See ``docs/M3_M4_HANDOFF.md`` §6.
"""

from __future__ import annotations

import hashlib
import json
import re
import subprocess
import sys
import tempfile
import traceback
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable

import click

# Anchored at the repo root: this file lives at
# classifier-training/src/classifier_training/ci/regression_check.py — five
# parents up is the repo root.
_REPO_ROOT = Path(__file__).resolve().parents[4]


# Default inputs. Resolved at click-parse time so callers can override anywhere
# along the chain (CLI flag, env via click, programmatic invocation).
DEFAULT_BASELINE = _REPO_ROOT / "eval/runs/phaseF_full_20260509_162556/metrics.json"
DEFAULT_PREFLIGHT_JSONL = _REPO_ROOT / "datasets/preflight/preflight_v1.0.0_regression.jsonl"
DEFAULT_MEMORY_JSONL = _REPO_ROOT / "datasets/memory/memory_v1.0.0_regression.jsonl"
DEFAULT_PREFLIGHT_MANIFEST = _REPO_ROOT / "datasets/preflight/MANIFEST.md"
DEFAULT_MEMORY_MANIFEST = _REPO_ROOT / "datasets/memory/MANIFEST.md"

DEFAULT_REGRESSION_THRESHOLD_PP: float = 2.0


# Gate metrics. Each entry is a dotted path into the metrics.json emitted by
# ``ct-eval-classifier``. Excludes informational fields (per-class support
# counts, threshold sweeps, host-CPU latency proxy) — those are diagnostics,
# not regression signals.
GATE_METRICS: tuple[str, ...] = (
    # Pre-flight regression-split: §7 GATE proxies + per-class.
    "preflight.regression.three_band.high_band_precision",
    "preflight.regression.three_band.time_sensitive_recall",
    "preflight.regression.three_band.high_band_recall",
    "preflight.regression.per_class.search_required.precision",
    "preflight.regression.per_class.search_required.recall",
    "preflight.regression.per_class.search_required.f1-score",
    "preflight.regression.per_class.search_not_required.precision",
    "preflight.regression.per_class.search_not_required.recall",
    "preflight.regression.per_class.search_not_required.f1-score",
    "preflight.regression.adversarial_pair_accuracy",
    "preflight.regression.macro_f1",
    "preflight.regression.accuracy",
    # Memory regression-split.
    "memory.regression.presence_precision",
    "memory.regression.presence_recall",
    "memory.regression.presence_f1",
    "memory.regression.presence_accuracy",
    "memory.regression.forget_command_accuracy",
    "memory.regression.remember_command_accuracy",
    "memory.regression.category_macro_f1",
)


EXIT_PASS = 0
EXIT_SHA_MISMATCH = 1
EXIT_REGRESSION = 2
EXIT_ERROR = 3


@dataclass(frozen=True)
class MetricDiff:
    metric: str
    baseline: float | None
    candidate: float | None
    delta_pp: float | None  # candidate - baseline, in percentage points

    @property
    def regressed(self) -> bool:
        return self.delta_pp is not None and self.delta_pp < 0

    @property
    def missing(self) -> bool:
        return self.candidate is None or self.baseline is None


def sha256_of_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(64 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def parse_manifest_sha(manifest_path: Path, version: str = "v1.0.0") -> str:
    """Extract the SHA-256 of [version]'s regression file from a MANIFEST.md.

    The Releases table has rows of the form
    ``| v1.0.0 | 11,670 | ✅ Frozen | 2026-05-09 | `<64-hex>` |``. We look
    for ``| <version> |`` followed by any cells then a backticked hex string.
    """
    text = manifest_path.read_text()
    pattern = (
        r"\|\s*" + re.escape(version) + r"\s*\|"  # version cell
        r"[^\n]*?"                                  # other cells
        r"`([0-9a-f]{64})`"                         # the SHA cell
    )
    match = re.search(pattern, text)
    if not match:
        raise ValueError(f"{version} SHA-256 row not found in {manifest_path}")
    return match.group(1)


def get_nested(d: Any, dotted: str) -> Any:
    """Walk a dotted path into nested dicts. Returns ``None`` on any miss."""
    cur = d
    for key in dotted.split("."):
        if not isinstance(cur, dict) or key not in cur:
            return None
        cur = cur[key]
    return cur


def diff_metrics(
    baseline: dict,
    candidate: dict,
    metrics: Iterable[str] = GATE_METRICS,
) -> list[MetricDiff]:
    """Compute (candidate - baseline) per metric in percentage points."""
    diffs: list[MetricDiff] = []
    for metric in metrics:
        b = get_nested(baseline, metric)
        c = get_nested(candidate, metric)
        delta = None
        if isinstance(b, (int, float)) and isinstance(c, (int, float)):
            # All gate metrics are 0-1 ratios; convert to percentage points.
            delta = (float(c) - float(b)) * 100.0
        diffs.append(MetricDiff(metric=metric, baseline=b, candidate=c, delta_pp=delta))
    return diffs


_FP_EPSILON_PP = 1e-6


def regressions(diffs: Iterable[MetricDiff], threshold_pp: float) -> list[MetricDiff]:
    """A regression is delta_pp < -threshold_pp (strictly worse than budget).

    Equality (exactly -threshold_pp) is treated as PASS so a 2pp drop with
    the default threshold doesn't fail. We allow a 1e-6 pp epsilon to soak
    up float-arithmetic noise (e.g., 0.48 - 0.5 == -0.02000…018), which
    would otherwise trip the strict inequality on values that are
    semantically equal. Missing metrics are surfaced as failures so we
    never silently skip a renamed key.
    """
    out: list[MetricDiff] = []
    for d in diffs:
        if d.missing:
            out.append(d)
        elif d.delta_pp is not None and d.delta_pp < -(threshold_pp + _FP_EPSILON_PP):
            out.append(d)
    return out


def format_diff_table(diffs: list[MetricDiff], threshold_pp: float) -> str:
    rows = [
        f"{'metric':<70}  {'baseline':>10}  {'candidate':>10}  {'Δ pp':>8}  {'gate':<6}"
    ]
    rows.append("-" * len(rows[0]))
    for d in diffs:
        if d.missing:
            base = f"{d.baseline}" if d.baseline is not None else "MISSING"
            cand = f"{d.candidate}" if d.candidate is not None else "MISSING"
            rows.append(f"{d.metric:<70}  {base:>10}  {cand:>10}  {'—':>8}  {'MISS':<6}")
            continue
        gate = "OK"
        if d.delta_pp is not None and d.delta_pp < -threshold_pp:
            gate = "FAIL"
        sign = "+" if (d.delta_pp or 0) >= 0 else ""
        rows.append(
            f"{d.metric:<70}  {d.baseline:>10.4f}  {d.candidate:>10.4f}  "
            f"{sign}{d.delta_pp:>7.2f}  {gate:<6}"
        )
    return "\n".join(rows)


def run_eval(
    ckpt: Path,
    preflight_jsonl: Path,
    memory_jsonl: Path,
    output_dir: Path,
) -> Path:
    """Invoke ``ct-eval-classifier --split regression``. Returns the path
    to the freshly-written ``metrics.json``.

    Uses ``sys.executable -m classifier_training.training.eval`` so the
    subprocess runs under the same Python interpreter as the gate,
    bypassing any PATH-lookup issues with the catalog-installed
    ``ct-eval-classifier`` shim.
    """
    cmd = [
        sys.executable,
        "-m",
        "classifier_training.training.eval",
        "--ckpt",
        str(ckpt),
        "--preflight-jsonl",
        str(preflight_jsonl),
        "--memory-jsonl",
        str(memory_jsonl),
        "--output-dir",
        str(output_dir),
        "--split",
        "regression",
    ]
    result = subprocess.run(cmd, capture_output=True, text=True)
    metrics_path = output_dir / "metrics.json"
    if result.returncode != 0 or not metrics_path.exists():
        raise RuntimeError(
            f"ct-eval-classifier failed (exit {result.returncode}).\n"
            f"stdout:\n{result.stdout}\nstderr:\n{result.stderr}"
        )
    return metrics_path


@click.command()
@click.option(
    "--ckpt",
    type=click.Path(exists=True, dir_okay=False, path_type=Path),
    required=True,
    help="Candidate checkpoint (.pt) to evaluate against the v1.0 baseline.",
)
@click.option(
    "--baseline",
    type=click.Path(exists=True, dir_okay=False, path_type=Path),
    default=DEFAULT_BASELINE,
    show_default=True,
    help="Path to baseline metrics.json (v1.0 by default).",
)
@click.option(
    "--preflight-jsonl",
    type=click.Path(exists=True, dir_okay=False, path_type=Path),
    default=DEFAULT_PREFLIGHT_JSONL,
    show_default=True,
)
@click.option(
    "--memory-jsonl",
    type=click.Path(exists=True, dir_okay=False, path_type=Path),
    default=DEFAULT_MEMORY_JSONL,
    show_default=True,
)
@click.option(
    "--preflight-manifest",
    type=click.Path(exists=True, dir_okay=False, path_type=Path),
    default=DEFAULT_PREFLIGHT_MANIFEST,
    show_default=True,
)
@click.option(
    "--memory-manifest",
    type=click.Path(exists=True, dir_okay=False, path_type=Path),
    default=DEFAULT_MEMORY_MANIFEST,
    show_default=True,
)
@click.option(
    "--output-dir",
    type=click.Path(file_okay=False, path_type=Path),
    default=None,
    help="Where ct-eval-classifier writes (default: temp dir).",
)
@click.option(
    "--threshold-pp",
    type=float,
    default=DEFAULT_REGRESSION_THRESHOLD_PP,
    show_default=True,
    help="Per-metric regression budget in percentage points.",
)
@click.option(
    "--skip-eval",
    is_flag=True,
    default=False,
    help="Treat --ckpt as a metrics.json (skip running ct-eval-classifier).",
)
def main(
    ckpt: Path,
    baseline: Path,
    preflight_jsonl: Path,
    memory_jsonl: Path,
    preflight_manifest: Path,
    memory_manifest: Path,
    output_dir: Path | None,
    threshold_pp: float,
    skip_eval: bool,
) -> None:
    """Local regression gate (M4 / WS-14). Exits non-zero on regression."""
    try:
        # 1. Verify regression-set hashes against MANIFEST.md.
        for label, manifest, jsonl in (
            ("preflight", preflight_manifest, preflight_jsonl),
            ("memory", memory_manifest, memory_jsonl),
        ):
            expected = parse_manifest_sha(manifest)
            actual = sha256_of_file(jsonl)
            if expected != actual:
                click.echo(
                    f"FAIL: {label} regression-split SHA-256 mismatch.\n"
                    f"  expected: {expected}\n"
                    f"  actual:   {actual}\n"
                    f"  jsonl:    {jsonl}\n"
                    f"  manifest: {manifest}\n"
                    "The frozen regression file changed. A patch release that "
                    "touches the regression split requires a major version bump "
                    "and a fresh classifier eval per the manifest policy.",
                    err=True,
                )
                sys.exit(EXIT_SHA_MISMATCH)

        # 2. Resolve candidate metrics. By default, run ct-eval-classifier
        #    against the candidate ckpt; --skip-eval lets callers feed a
        #    pre-computed metrics.json (used by the smoke test against the
        #    baseline, and by future hosted-CI that runs the eval separately).
        candidate_metrics: dict
        if skip_eval:
            click.echo(f"Reading candidate metrics from {ckpt} (skip-eval)")
            candidate_metrics = json.loads(ckpt.read_text())
        else:
            with tempfile.TemporaryDirectory(prefix="ct-regression-") as tmp:
                resolved_output = output_dir or Path(tmp)
                resolved_output.mkdir(parents=True, exist_ok=True)
                click.echo(f"Running ct-eval-classifier against {ckpt}")
                metrics_path = run_eval(
                    ckpt=ckpt,
                    preflight_jsonl=preflight_jsonl,
                    memory_jsonl=memory_jsonl,
                    output_dir=resolved_output,
                )
                # Read inside the `with` so the temp dir is still alive.
                candidate_metrics_text = metrics_path.read_text()
                candidate_metrics = json.loads(candidate_metrics_text)
                if output_dir is None:
                    # Stash a copy alongside the baseline so the human can
                    # inspect after the script exits and the temp dir is gone.
                    snapshot = baseline.parent.parent / "regression_check_candidate.json"
                    snapshot.write_text(candidate_metrics_text)
                    click.echo(f"Candidate metrics snapshot: {snapshot}")

        baseline_metrics = json.loads(baseline.read_text())

        # 3. Diff and gate.
        diffs = diff_metrics(baseline_metrics, candidate_metrics)
        click.echo(format_diff_table(diffs, threshold_pp))

        failed = regressions(diffs, threshold_pp)
        if failed:
            click.echo(
                f"\nFAIL: {len(failed)} of {len(diffs)} gate metric(s) regressed "
                f"by more than {threshold_pp:.1f}pp:",
                err=True,
            )
            for d in failed:
                if d.missing:
                    click.echo(f"  - {d.metric}: MISSING (baseline={d.baseline}, candidate={d.candidate})", err=True)
                else:
                    click.echo(f"  - {d.metric}: {d.delta_pp:+.2f}pp (baseline={d.baseline:.4f}, candidate={d.candidate:.4f})", err=True)
            sys.exit(EXIT_REGRESSION)

        click.echo(
            f"\nPASS: all {len(diffs)} gate metric(s) within {threshold_pp:.1f}pp of baseline."
        )
        sys.exit(EXIT_PASS)

    except SystemExit:
        raise
    except Exception:
        click.echo(
            f"ERROR: regression gate infrastructure failure:\n{traceback.format_exc()}",
            err=True,
        )
        sys.exit(EXIT_ERROR)


if __name__ == "__main__":
    main()
