"""Unit tests for the WS-14 regression gate (`ct-regression-check`).

Covers the pure logic — SHA verification, manifest parsing, metric diffing,
threshold edge cases — without invoking ct-eval-classifier (that's
exercised by the smoke-test runs against the real v1.0 ckpt).
"""

from __future__ import annotations

import hashlib
from pathlib import Path

import pytest

from classifier_training.ci.regression_check import (
    DEFAULT_REGRESSION_THRESHOLD_PP,
    GATE_METRICS,
    MetricDiff,
    diff_metrics,
    get_nested,
    parse_manifest_sha,
    regressions,
    sha256_of_file,
)


# -- Hashing ---------------------------------------------------------------

def test_sha256_of_file_matches_hashlib(tmp_path: Path) -> None:
    f = tmp_path / "x.txt"
    payload = b"hello\nworld\n" * 1000
    f.write_bytes(payload)
    expected = hashlib.sha256(payload).hexdigest()
    assert sha256_of_file(f) == expected


# -- Manifest parsing ------------------------------------------------------

def test_parse_manifest_sha_extracts_v1_row(tmp_path: Path) -> None:
    manifest = tmp_path / "MANIFEST.md"
    manifest.write_text(
        "## Releases\n\n"
        "| Version | Examples | Status | Cut date | Regression SHA-256 |\n"
        "|---|---:|---|---|---|\n"
        "| v1.0.0 | 11,670 | ✅ Frozen | 2026-05-09 | `9724f57840a4fd73ebdc318911ce7a79c6b43d3c093e314983538350521be544` |\n"
    )
    sha = parse_manifest_sha(manifest)
    assert sha == "9724f57840a4fd73ebdc318911ce7a79c6b43d3c093e314983538350521be544"


def test_parse_manifest_sha_picks_correct_version(tmp_path: Path) -> None:
    manifest = tmp_path / "MANIFEST.md"
    manifest.write_text(
        "| Version | … | Regression SHA-256 |\n"
        "|---|---|---|\n"
        "| v0.9.0 | … | `aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa` |\n"
        "| v1.0.0 | … | `bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb` |\n"
        "| v1.1.0 | … | `cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc` |\n"
    )
    assert parse_manifest_sha(manifest, "v1.0.0") == "b" * 64
    assert parse_manifest_sha(manifest, "v0.9.0") == "a" * 64
    assert parse_manifest_sha(manifest, "v1.1.0") == "c" * 64


def test_parse_manifest_sha_raises_when_version_missing(tmp_path: Path) -> None:
    manifest = tmp_path / "MANIFEST.md"
    manifest.write_text("nothing here")
    with pytest.raises(ValueError, match="v1.0.0"):
        parse_manifest_sha(manifest)


# -- Nested key walker -----------------------------------------------------

def test_get_nested_walks_dotted_path() -> None:
    d = {"a": {"b": {"c": 7}}}
    assert get_nested(d, "a.b.c") == 7
    assert get_nested(d, "a.b") == {"c": 7}


def test_get_nested_returns_none_for_missing_segments() -> None:
    d = {"a": {"b": 1}}
    assert get_nested(d, "a.x") is None
    assert get_nested(d, "z.y") is None
    assert get_nested(d, "a.b.c") is None  # b is a leaf, can't recurse


# -- Diff logic ------------------------------------------------------------

def _make_baseline_with(values: dict[str, float]) -> dict:
    """Build a metrics-shape dict where each gate metric resolves to the
    matching value in [values]. Unspecified metrics default to 0.5."""
    out: dict = {}
    for metric in GATE_METRICS:
        cur = out
        parts = metric.split(".")
        for k in parts[:-1]:
            cur = cur.setdefault(k, {})
        cur[parts[-1]] = values.get(metric, 0.5)
    return out


def test_diff_metrics_reports_zero_delta_when_identical() -> None:
    base = _make_baseline_with({})
    cand = _make_baseline_with({})
    diffs = diff_metrics(base, cand)
    assert all(d.delta_pp == pytest.approx(0.0) for d in diffs)
    assert regressions(diffs, DEFAULT_REGRESSION_THRESHOLD_PP) == []


def test_diff_metrics_flags_regression_above_threshold() -> None:
    base = _make_baseline_with({})
    # Drop one metric by 3pp → exceeds the 2pp default threshold.
    target = "memory.regression.presence_precision"
    cand = _make_baseline_with({target: 0.5 - 0.03})
    diffs = diff_metrics(base, cand)
    failed = regressions(diffs, threshold_pp=2.0)
    assert len(failed) == 1
    assert failed[0].metric == target
    assert failed[0].delta_pp == pytest.approx(-3.0, abs=1e-6)


def test_diff_metrics_passes_at_exactly_2pp_drop() -> None:
    base = _make_baseline_with({})
    target = "preflight.regression.macro_f1"
    cand = _make_baseline_with({target: 0.5 - 0.02})  # exactly -2.0pp
    diffs = diff_metrics(base, cand)
    failed = regressions(diffs, threshold_pp=2.0)
    assert failed == []  # equality is PASS — strictly less-than is the gate


def test_diff_metrics_fails_at_2_01pp_drop() -> None:
    base = _make_baseline_with({})
    target = "preflight.regression.macro_f1"
    cand = _make_baseline_with({target: 0.5 - 0.0201})
    diffs = diff_metrics(base, cand)
    failed = regressions(diffs, threshold_pp=2.0)
    assert [d.metric for d in failed] == [target]


def test_diff_metrics_ignores_improvements() -> None:
    base = _make_baseline_with({})
    target = "preflight.regression.three_band.high_band_precision"
    cand = _make_baseline_with({target: 0.95})  # +45pp
    diffs = diff_metrics(base, cand)
    failed = regressions(diffs, threshold_pp=2.0)
    assert failed == []  # candidate is BETTER than baseline; no regression


def test_diff_metrics_flags_missing_keys() -> None:
    base = _make_baseline_with({})
    cand = {"empty": True}  # nothing matches
    diffs = diff_metrics(base, cand)
    failed = regressions(diffs, threshold_pp=2.0)
    # Every gate metric is missing in the candidate → every metric fails.
    assert len(failed) == len(GATE_METRICS)
    assert all(d.missing for d in failed)


def test_diff_metrics_handles_partial_candidate() -> None:
    """Candidate missing a metric should fail gate, not silently pass."""
    base = _make_baseline_with({})
    cand = _make_baseline_with({})
    # Surgically delete one nested key from candidate.
    del cand["memory"]["regression"]["presence_precision"]
    diffs = diff_metrics(base, cand)
    failed = regressions(diffs, threshold_pp=2.0)
    assert len(failed) == 1
    assert failed[0].metric == "memory.regression.presence_precision"
    assert failed[0].missing


def test_metric_diff_regressed_property() -> None:
    assert MetricDiff("x", 0.9, 0.8, -10.0).regressed is True
    assert MetricDiff("x", 0.8, 0.9, +10.0).regressed is False
    assert MetricDiff("x", 0.5, 0.5, 0.0).regressed is False
    assert MetricDiff("x", None, 0.5, None).regressed is False  # missing != regressed
