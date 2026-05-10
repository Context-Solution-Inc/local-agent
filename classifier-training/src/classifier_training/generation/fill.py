"""Stratified generation driver — `ct-fill`.

Reads the §2.2 / §3.2 stratification target table baked into this module,
inspects the current JSONL file, and loops `ct-generate-*` calls on whichever
cell is most underweight until every cell hits target. Resumable: re-running
picks up exactly where it left off.

For preflight, a "cell" is (label, category). For memory, a cell is
(density, hard_case_or_density_only). Within preflight cells we rotate
target_confidence across batches so the high/medium/low confidence
proportions land near §2.2's 70/25/5.

Run examples:

    ct-fill preflight --out datasets/preflight/preflight_v0.1.0.jsonl
    ct-fill memory    --out datasets/memory/memory_v0.1.0.jsonl
    ct-fill preflight --out ... --max-batches 50      # cap per session
    ct-fill preflight --out ... --multiplier 0.10     # generate 10% of each cell

`--multiplier 1.0` (default) targets the full v1.0 dataset. Use a smaller
multiplier for incremental verification batches.
"""

from __future__ import annotations

import random
import time
from collections import Counter
from pathlib import Path
from typing import Any

import click
import jsonlines
from rich.console import Console
from rich.table import Table

from ..datasets.dedup import _canonicalize, _text_for_embedding
from ..datasets.schemas import (
    MemoryExtractionExample,
    PreflightCategory,
    PreflightConfidence,
    PreflightExample,
    PreflightLabel,
    Source,
    SplitName,
)
from .generate import (
    DEFAULT_OLLAMA_MODEL,
    _append_jsonl,
    _backend,
    _call_backend,
    _parse_array,
    _render_prompt,
    _resolve_model,
    _sample_prior,
    _validate_and_assign,
)

_console = Console()


# =============================================================================
# Pre-flight stratification targets (M3_PLAN.md §3 Phase B)
# =============================================================================

# (label, category) -> target_count at multiplier=1.0
PREFLIGHT_TARGETS: dict[tuple[PreflightLabel, PreflightCategory], int] = {
    # search_required = 4,000 total
    (PreflightLabel.SEARCH_REQUIRED, PreflightCategory.SPORTS_RECENT): 700,
    (PreflightLabel.SEARCH_REQUIRED, PreflightCategory.SPORTS_UPCOMING): 350,
    (PreflightLabel.SEARCH_REQUIRED, PreflightCategory.MARKETS_CURRENT): 600,
    (PreflightLabel.SEARCH_REQUIRED, PreflightCategory.WEATHER): 400,
    (PreflightLabel.SEARCH_REQUIRED, PreflightCategory.NEWS_CURRENT): 700,
    (PreflightLabel.SEARCH_REQUIRED, PreflightCategory.PRICES_PRODUCTS): 350,
    (PreflightLabel.SEARCH_REQUIRED, PreflightCategory.STATUS_RECENT): 450,
    (PreflightLabel.SEARCH_REQUIRED, PreflightCategory.SCHEDULES_EVENTS): 450,
    # search_not_required = 4,500 total
    (PreflightLabel.SEARCH_NOT_REQUIRED, PreflightCategory.GENERAL_KNOWLEDGE): 900,
    (PreflightLabel.SEARCH_NOT_REQUIRED, PreflightCategory.SETTLED_HISTORY): 600,
    (PreflightLabel.SEARCH_NOT_REQUIRED, PreflightCategory.OPINION_REASONING): 600,
    (PreflightLabel.SEARCH_NOT_REQUIRED, PreflightCategory.CODING_MATH): 800,
    (PreflightLabel.SEARCH_NOT_REQUIRED, PreflightCategory.CREATIVE): 400,
    (PreflightLabel.SEARCH_NOT_REQUIRED, PreflightCategory.PERSONAL_MEMORY): 600,
    (PreflightLabel.SEARCH_NOT_REQUIRED, PreflightCategory.META): 600,
    # ambiguous = 1,500 total
    (PreflightLabel.AMBIGUOUS, PreflightCategory.AMBIGUOUS): 1_500,
}

# Confidence shape per (decisive) cell: 70% high, 25% medium, 5% low.
PREFLIGHT_CONFIDENCE_SHAPE: dict[PreflightConfidence, float] = {
    PreflightConfidence.HIGH: 0.70,
    PreflightConfidence.MEDIUM: 0.25,
    PreflightConfidence.LOW: 0.05,
}

# Ambiguous label is forced to medium confidence per schema; no shape needed.


# =============================================================================
# Memory stratification targets (M3_PLAN.md §3 Phase B)
# =============================================================================

# Density-only target counts (per §3.2). The empty exemplars in the prompt
# already cover transient query / hypothetical / third-party / search-as-statement
# patterns, so we don't need to drive those as separate cells; aggregated
# empty counts plus the hard-command quotas below give us §3.4/§3.5 coverage.
MEMORY_TARGETS: dict[tuple[str, str | None], int] = {
    ("empty", None): 3_600,
    ("one", None): 1_680,
    ("multi", None): 720,
    # §3.5 hard minimums — counted by explicit_command on each row.
    ("mixed", "forget_command"): 250,
    ("mixed", "remember_command"): 250,
}


# =============================================================================
# Common helpers
# =============================================================================


def _load_jsonl_or_empty(path: Path) -> list[dict[str, Any]]:
    if not path.exists():
        return []
    with jsonlines.open(path) as reader:
        return list(reader)


def _existing_canonicals(rows: list[dict[str, Any]]) -> set[str]:
    """Set of canonical-form texts already present in the file. Used to drop
    duplicates as they're generated, before they count toward cell targets."""
    out: set[str] = set()
    for r in rows:
        try:
            out.add(_canonicalize(_text_for_embedding(r)))
        except (ValueError, KeyError):
            continue
    return out


def _drop_in_batch_dups(
    accepted: list[Any],
    existing: set[str],
) -> tuple[list[Any], int]:
    """Drop accepted examples whose canonical text matches an existing row OR
    a sibling earlier in the batch. Returns (kept, n_dropped)."""
    kept: list[Any] = []
    seen = set(existing)
    dropped = 0
    for ex in accepted:
        # Pydantic models — extract the right field via dict.
        d = ex.model_dump(mode="json")
        try:
            text = _text_for_embedding(d)
        except (ValueError, KeyError):
            kept.append(ex)
            continue
        canon = _canonicalize(text)
        if not canon:
            kept.append(ex)
            continue
        if canon in seen:
            dropped += 1
            continue
        seen.add(canon)
        kept.append(ex)
    return kept, dropped


def _density_of(row: dict[str, Any]) -> str:
    n = len(row.get("memories_to_extract") or [])
    if n == 0:
        return "empty"
    if n == 1:
        return "one"
    return "multi"


# =============================================================================
# Pre-flight driver
# =============================================================================


def _preflight_cell_counts(
    rows: list[dict[str, Any]],
) -> dict[tuple[PreflightLabel, PreflightCategory], int]:
    counts: Counter[tuple[PreflightLabel, PreflightCategory]] = Counter()
    for r in rows:
        try:
            label = PreflightLabel(r["label"])
            category = PreflightCategory(r["category"])
        except (KeyError, ValueError):
            continue
        counts[(label, category)] += 1
    return dict(counts)


def _preflight_confidence_counts(
    rows: list[dict[str, Any]],
    label: PreflightLabel,
    category: PreflightCategory,
) -> Counter[PreflightConfidence]:
    out: Counter[PreflightConfidence] = Counter()
    for r in rows:
        if r.get("label") != label.value or r.get("category") != category.value:
            continue
        try:
            out[PreflightConfidence(r["confidence"])] += 1
        except (KeyError, ValueError):
            continue
    return out


def _pick_preflight_confidence_target(
    cell: tuple[PreflightLabel, PreflightCategory],
    rows: list[dict[str, Any]],
) -> str:
    """Return the confidence level that's most underweight in this cell vs §2.2."""
    label, _ = cell
    if label is PreflightLabel.AMBIGUOUS:
        return "medium"  # ambiguous is forced medium per schema
    counts = _preflight_confidence_counts(rows, *cell)
    total = sum(counts.values()) or 1
    biggest_deficit = ("high", -1.0)
    for conf, want_share in PREFLIGHT_CONFIDENCE_SHAPE.items():
        have_share = counts[conf] / total
        deficit = want_share - have_share
        if deficit > biggest_deficit[1]:
            biggest_deficit = (conf.value, deficit)
    return biggest_deficit[0]


def _print_preflight_progress(
    rows: list[dict[str, Any]],
    targets: dict[tuple[PreflightLabel, PreflightCategory], int],
) -> None:
    counts = _preflight_cell_counts(rows)
    total = len(rows)
    target_total = sum(targets.values())
    table = Table(title=f"Pre-flight progress: {total} / {target_total}")
    table.add_column("Label")
    table.add_column("Category")
    table.add_column("Have", justify="right")
    table.add_column("Target", justify="right")
    table.add_column("Status")
    for cell, want in targets.items():
        have = counts.get(cell, 0)
        if have >= want:
            status = f"[green]✓ +{have - want}[/green]"
        else:
            status = f"[yellow]−{want - have}[/yellow]"
        table.add_row(cell[0].value, cell[1].value, str(have), str(want), status)
    _console.print(table)


def _drive_preflight(
    out: Path,
    targets: dict[tuple[PreflightLabel, PreflightCategory], int],
    max_batches: int,
    batch_size: int,
    model: str,
    max_tokens: int,
    seed: int | None,
) -> None:
    rng = random.Random(seed)
    rows = _load_jsonl_or_empty(out)
    batches_done = 0
    batches_failed = 0
    while batches_done < max_batches:
        rows = _load_jsonl_or_empty(out)
        counts = _preflight_cell_counts(rows)
        gaps = {cell: targets[cell] - counts.get(cell, 0) for cell in targets}
        underweight = [(cell, gap) for cell, gap in gaps.items() if gap > 0]
        if not underweight:
            _console.print("[bold green]All pre-flight cells filled.[/bold green]")
            break
        # Largest absolute gap wins.
        underweight.sort(key=lambda x: -x[1])
        cell, gap = underweight[0]
        label, category = cell
        confidence = _pick_preflight_confidence_target(cell, rows)
        n = min(batch_size, gap)
        prior = _sample_prior(out, prefix="preflight_", k=3, rng=rng)
        prompt = _render_prompt(
            "preflight_generation.j2",
            count=n,
            target_label=label.value,
            target_categories=[category.value],
            target_confidence=confidence,
            prior_examples=prior,
        )
        try:
            raw = _call_backend(prompt, model=model, max_tokens=max_tokens)
            parsed = _parse_array(raw)
        except Exception as e:  # noqa: BLE001
            batches_failed += 1
            _console.print(f"[red]Generation failed: {e}[/red]")
            if batches_failed >= 15:
                _console.print("[red]Fifteen consecutive failures — bailing.[/red]")
                break
            continue
        accepted, rejected = _validate_and_assign(
            raw_examples=parsed,
            schema=PreflightExample,
            out_path=out,
            id_prefix="preflight_",
            rng=rng,
            source=Source.SYNTHETIC_V1,
        )
        existing_canon = _existing_canonicals(rows)
        kept, dropped_dup = _drop_in_batch_dups(accepted, existing_canon)
        _append_jsonl(out, kept)
        batches_done += 1
        batches_failed = 0
        _console.print(
            f"[{batches_done}/{max_batches}] cell=({label.value},{category.value}) "
            f"conf={confidence} kept={len(kept)} dup={dropped_dup} "
            f"rej={len(rejected)} gap_was={gap}"
        )

    rows = _load_jsonl_or_empty(out)
    _print_preflight_progress(rows, targets)


# =============================================================================
# Memory driver
# =============================================================================


def _memory_cell_counts(
    rows: list[dict[str, Any]],
) -> dict[tuple[str, str | None], int]:
    """Counts examples in each (density, hard_case) cell.

    Hard case is detected as follows:
      - explicit_command="forget" → ("mixed", "forget_command")
      - explicit_command="remember" → ("mixed", "remember_command")
      - otherwise: counted only by density, with hard_case=None unless the
        row was generated under one of the dedicated hard-case prompts (we
        can't distinguish in retrospect, so we count toward the misc bucket).
    """
    counts: Counter[tuple[str, str | None]] = Counter()
    for r in rows:
        density = _density_of(r)
        cmd = r.get("explicit_command")
        if cmd == "forget":
            counts[("mixed", "forget_command")] += 1
        elif cmd == "remember":
            counts[("mixed", "remember_command")] += 1
        else:
            # density-only counts (we don't retroactively tag empties to a
            # specific hard case bucket; "empty" rows count toward the misc
            # bucket key (None) for budgeting.
            counts[(density, None)] += 1
    return dict(counts)


def _print_memory_progress(
    rows: list[dict[str, Any]],
    targets: dict[tuple[str, str | None], int],
) -> None:
    gaps = _memory_gaps(rows, targets)
    total = len(rows)
    target_total = sum(targets.values())
    table = Table(title=f"Memory progress: {total} / {target_total}")
    table.add_column("Density")
    table.add_column("Hard case")
    table.add_column("Have", justify="right")
    table.add_column("Target", justify="right")
    table.add_column("Status")
    for cell, want in targets.items():
        gap = gaps.get(cell, 0)
        have = want - gap if gap > 0 else want + (-gap)
        status = (
            f"[green]✓ +{have - want}[/green]"
            if have >= want
            else f"[yellow]−{want - have}[/yellow]"
        )
        table.add_row(
            cell[0], cell[1] or "(any)", str(have), str(want), status
        )
    _console.print(table)


def _memory_gaps(
    rows: list[dict[str, Any]],
    targets: dict[tuple[str, str | None], int],
) -> dict[tuple[str, str | None], int]:
    """Compute remaining gap per cell."""
    gaps: dict[tuple[str, str | None], int] = {}
    # Hard-command cells: count by explicit_command.
    for cell in (("mixed", "forget_command"), ("mixed", "remember_command")):
        if cell not in targets:
            continue
        cmd = "forget" if "forget" in (cell[1] or "") else "remember"
        have = sum(1 for r in rows if r.get("explicit_command") == cmd)
        gaps[cell] = targets[cell] - have
    # Density cells: roll up all targets sharing a density into one bucket,
    # excluding forget/remember rows (they're tracked separately).
    for density in ("multi", "one", "empty"):
        cell_targets = [t for (d, _), t in targets.items() if d == density]
        if not cell_targets:
            continue
        density_target = sum(cell_targets)
        density_have = sum(
            1 for r in rows
            if _density_of(r) == density
            and r.get("explicit_command") not in ("forget", "remember")
        )
        gaps[(density, None)] = density_target - density_have
    return gaps


def _drive_memory(
    out: Path,
    targets: dict[tuple[str, str | None], int],
    max_batches: int,
    batch_size: int,
    model: str,
    max_tokens: int,
    seed: int | None,
) -> None:
    rng = random.Random(seed)
    batches_done = 0
    batches_failed = 0
    while batches_done < max_batches:
        rows = _load_jsonl_or_empty(out)
        gaps = _memory_gaps(rows, targets)
        underweight = [(cell, gap) for cell, gap in gaps.items() if gap > 0]
        if not underweight:
            _console.print("[bold green]All memory cells filled.[/bold green]")
            break
        # §3.5 hard-command minimums first; otherwise largest absolute gap.
        underweight.sort(
            key=lambda cg: (
                0 if cg[0] in (("mixed", "forget_command"), ("mixed", "remember_command")) else 1,
                -cg[1],
            )
        )
        cell, gap = underweight[0]
        density, hard_case = cell
        n = min(batch_size, gap)
        prior = _sample_prior(out, prefix="memory_", k=3, rng=rng)
        prompt = _render_prompt(
            "memory_generation.j2",
            count=n,
            target_density=density,
            hard_case=hard_case,
            prior_examples=prior,
        )
        try:
            raw = _call_backend(prompt, model=model, max_tokens=max_tokens)
            parsed = _parse_array(raw)
        except Exception as e:  # noqa: BLE001
            batches_failed += 1
            _console.print(f"[red]Generation failed: {e}[/red]")
            if batches_failed >= 15:
                _console.print("[red]Fifteen consecutive failures — bailing.[/red]")
                break
            continue
        accepted, rejected = _validate_and_assign(
            raw_examples=parsed,
            schema=MemoryExtractionExample,
            out_path=out,
            id_prefix="memory_",
            rng=rng,
            source=Source.SYNTHETIC_V1,
        )
        existing_canon = _existing_canonicals(rows)
        kept, dropped_dup = _drop_in_batch_dups(accepted, existing_canon)
        _append_jsonl(out, kept)
        batches_done += 1
        batches_failed = 0
        _console.print(
            f"[{batches_done}/{max_batches}] cell=({density},{hard_case}) "
            f"kept={len(kept)} dup={dropped_dup} rej={len(rejected)} "
            f"gap_was={gap}"
        )

    rows = _load_jsonl_or_empty(out)
    _print_memory_progress(rows, targets)


# =============================================================================
# CLI
# =============================================================================


@click.group()
def main() -> None:
    """Stratified generation driver for M3 Phase B."""


def _scaled(targets: dict[Any, int], multiplier: float) -> dict[Any, int]:
    return {cell: max(1, int(round(want * multiplier))) for cell, want in targets.items()}


@main.command()
@click.option(
    "--out",
    type=click.Path(dir_okay=False, path_type=Path),
    default=Path("../datasets/preflight/preflight_v0.1.0.jsonl"),
)
@click.option("--max-batches", default=2_000, type=int, help="Hard cap per session.")
@click.option("--batch-size", default=8, type=int)
@click.option("--multiplier", default=1.0, type=float, help="Scale all cell targets.")
@click.option("--model", default=DEFAULT_OLLAMA_MODEL)
@click.option("--max-tokens", default=4096, type=int)
@click.option("--seed", default=None, type=int)
def preflight(
    out: Path,
    max_batches: int,
    batch_size: int,
    multiplier: float,
    model: str,
    max_tokens: int,
    seed: int | None,
) -> None:
    """Fill the pre-flight dataset against §2.2 targets."""
    targets = _scaled(PREFLIGHT_TARGETS, multiplier)
    _console.print(
        f"[bold]ct-fill preflight[/bold] backend={_backend()} model={_resolve_model(model)} "
        f"out={out} multiplier={multiplier} target_total={sum(targets.values())}"
    )
    started = time.monotonic()
    _drive_preflight(out, targets, max_batches, batch_size, model, max_tokens, seed)
    _console.print(f"[dim]Elapsed: {time.monotonic() - started:.1f}s[/dim]")


@main.command()
@click.option(
    "--out",
    type=click.Path(dir_okay=False, path_type=Path),
    default=Path("../datasets/memory/memory_v0.1.0.jsonl"),
)
@click.option("--max-batches", default=2_000, type=int)
@click.option("--batch-size", default=8, type=int)
@click.option("--multiplier", default=1.0, type=float)
@click.option("--model", default=DEFAULT_OLLAMA_MODEL)
@click.option("--max-tokens", default=4096, type=int)
@click.option("--seed", default=None, type=int)
def memory(
    out: Path,
    max_batches: int,
    batch_size: int,
    multiplier: float,
    model: str,
    max_tokens: int,
    seed: int | None,
) -> None:
    """Fill the memory dataset against §3.2 targets."""
    targets = _scaled(MEMORY_TARGETS, multiplier)
    _console.print(
        f"[bold]ct-fill memory[/bold] backend={_backend()} model={_resolve_model(model)} "
        f"out={out} multiplier={multiplier} target_total={sum(targets.values())}"
    )
    started = time.monotonic()
    _drive_memory(out, targets, max_batches, batch_size, model, max_tokens, seed)
    _console.print(f"[dim]Elapsed: {time.monotonic() - started:.1f}s[/dim]")


if __name__ == "__main__":
    main()
