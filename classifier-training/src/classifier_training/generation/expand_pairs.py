"""Adversarial pair expansion driver — `ct-expand-pairs`.

Iterates the hand-authored prototype pairs in
`classifier_training.generation.preflight_pair_prototypes`, calls Ollama with
the dedicated rephrasing prompt for each side, validates each rephrasing
against the PreflightExample schema, tags it with `pair_id` and `source=adversarial`,
forces one variant per side into the regression split, and appends to the
canonical dataset JSONL.

Resumable: re-running picks up only the pairs that don't yet have full
coverage in the output file.
"""

from __future__ import annotations

import random
import time
from collections import Counter
from pathlib import Path
from typing import Any

import click
import jsonlines
from pydantic import ValidationError
from rich.console import Console
from rich.table import Table

import json as _json

from ..datasets.dedup import _canonicalize, _text_for_embedding
from ..datasets.schemas import (
    MemoryExtractionExample,
    PreflightExample,
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
)
from .memory_hard_case_prototypes import (
    ALL_MEMORY_PROTOTYPES,
    MemoryFact,
    MemoryProto,
    MemoryProtoPair,
)
from .preflight_pair_prototypes import ALL_PROTOTYPES, PrototypePair, PrototypeSide

_console = Console()


# =============================================================================
# Helpers
# =============================================================================


def _existing_canonicals(rows: list[dict[str, Any]]) -> set[str]:
    out: set[str] = set()
    for r in rows:
        try:
            out.add(_canonicalize(_text_for_embedding(r)))
        except (ValueError, KeyError):
            continue
    return out


def _coverage_per_side(
    rows: list[dict[str, Any]],
) -> dict[tuple[str, str], int]:
    """Map (pair_id, query_canonical_root) -> count.

    Counts how many variants exist for each side. Since we don't store
    "which side of the pair" on the row, we group by pair_id and tally.
    """
    by_pair: Counter[str] = Counter()
    for r in rows:
        pid = r.get("pair_id")
        if pid:
            by_pair[pid] += 1
    return {(pid, "any"): n for pid, n in by_pair.items()}


def _next_id_idx(rows: list[dict[str, Any]]) -> int:
    max_seen = -1
    for r in rows:
        rid = r.get("id", "")
        if rid.startswith("preflight_"):
            try:
                n = int(rid[len("preflight_"):])
                if n > max_seen:
                    max_seen = n
            except ValueError:
                continue
    return max_seen + 1


def _expand_side(
    pair: PrototypePair,
    side: PrototypeSide,
    variants_per_side: int,
    model: str,
    max_tokens: int,
) -> list[dict[str, Any]]:
    """Returns a list of raw rephrasing dicts."""
    prompt = _render_prompt(
        "preflight_pair_expansion.j2",
        count=variants_per_side,
        original_query=side.query,
        label=side.label,
        confidence=side.confidence,
        category=side.category,
        rationale=side.rationale,
        pair_type=pair.pair_type,
    )
    raw = _call_backend(prompt, model=model, max_tokens=max_tokens)
    return _parse_array(raw)


def _materialize_examples(
    pair: PrototypePair,
    side: PrototypeSide,
    raw_variants: list[dict[str, Any]],
    next_idx: int,
    existing_canon: set[str],
    rng: random.Random,
    force_one_regression: bool,
) -> tuple[list[PreflightExample], list[tuple[str, str]], int]:
    """Build PreflightExample objects from raw rephrasings.

    - Ensures each variant validates against the schema.
    - Tags with pair_id, source=adversarial, and split.
    - Forces ONE variant from this side into the regression split if
      force_one_regression is True.
    - Drops in-batch duplicates and against existing_canon.

    Returns (accepted, rejected_with_reason, new_next_idx).
    """
    accepted: list[PreflightExample] = []
    rejected: list[tuple[str, str]] = []
    seen = set(existing_canon)
    forced_one = not force_one_regression  # if False at start, force on first kept
    # Always include the prototype query itself as a variant once (forced
    # regression) so the pair has a canonical exemplar.
    candidates: list[tuple[str, str]] = [
        (side.query, side.rationale)
    ]
    for v in raw_variants:
        q = v.get("query")
        r = v.get("rationale", side.rationale)
        if isinstance(q, str) and q.strip():
            candidates.append((q.strip(), r if isinstance(r, str) else side.rationale))

    for q, r in candidates:
        canon = _canonicalize(q)
        if not canon or canon in seen:
            rejected.append((q, "duplicate"))
            continue
        seen.add(canon)
        # Decide split — first accepted goes to regression if force flag set.
        if not forced_one:
            split = SplitName.REGRESSION.value
            forced_one = True
        else:
            # Mirror the rough train/val/test mix (no further regression).
            split = rng.choices(
                ["train", "val", "test"], weights=[0.78, 0.13, 0.09], k=1
            )[0]
        row = {
            "id": f"preflight_{next_idx:05d}",
            "query": q,
            "label": side.label,
            "confidence": side.confidence,
            "category": side.category,
            "rationale": r if r else side.rationale,
            "pair_id": pair.pair_id,
            "source": Source.ADVERSARIAL.value,
            "split": split,
        }
        try:
            example = PreflightExample(**row)
        except ValidationError as e:
            rejected.append((q, f"schema: {e.errors()[0]['msg'] if e.errors() else e}"))
            continue
        accepted.append(example)
        next_idx += 1
    return accepted, rejected, next_idx


# =============================================================================
# Driver
# =============================================================================


def _drive_preflight_pairs(
    out: Path,
    variants_per_side: int,
    model: str,
    max_tokens: int,
    seed: int | None,
    only_pair_id: str | None,
) -> None:
    rng = random.Random(seed)
    rows = list(jsonlines.open(out)) if out.exists() else []
    coverage = _coverage_per_side(rows)
    next_idx = _next_id_idx(rows)
    existing_canon = _existing_canonicals(rows)

    pairs = ALL_PROTOTYPES
    if only_pair_id:
        pairs = tuple(p for p in pairs if p.pair_id == only_pair_id)
        if not pairs:
            raise click.ClickException(
                f"No prototype with pair_id={only_pair_id!r}"
            )

    # Each side wants ~variants_per_side examples (plus the prototype itself).
    target_per_side = variants_per_side + 1
    target_per_pair = lambda p: target_per_side * len(p.sides)

    started = time.monotonic()
    pairs_done = 0
    pairs_skipped = 0
    total_accepted = 0
    total_rejected = 0

    for pair in pairs:
        have = coverage.get((pair.pair_id, "any"), 0)
        wanted = target_per_pair(pair)
        if have >= wanted:
            pairs_skipped += 1
            continue

        for side_idx, side in enumerate(pair.sides):
            try:
                raw = _expand_side(
                    pair=pair,
                    side=side,
                    variants_per_side=variants_per_side,
                    model=model,
                    max_tokens=max_tokens,
                )
            except Exception as e:  # noqa: BLE001
                _console.print(
                    f"[red]Expansion failed for {pair.pair_id}/{side.query[:40]}: {e}[/red]"
                )
                continue
            # First side of each pair gets the regression-forced flag; subsequent
            # sides also force one regression each so every pair/side ends up
            # with at least one regression example.
            accepted, rejected, next_idx = _materialize_examples(
                pair=pair,
                side=side,
                raw_variants=raw,
                next_idx=next_idx,
                existing_canon=existing_canon,
                rng=rng,
                force_one_regression=True,
            )
            for ex in accepted:
                existing_canon.add(_canonicalize(ex.query))
            _append_jsonl(out, accepted)
            total_accepted += len(accepted)
            total_rejected += len(rejected)
            _console.print(
                f"  pair={pair.pair_id} side[{side_idx}] kept={len(accepted)} "
                f"rej={len(rejected)}"
            )
        pairs_done += 1

    elapsed = time.monotonic() - started
    _console.print(
        f"\n[bold]Done.[/bold] pairs_done={pairs_done} skipped={pairs_skipped} "
        f"accepted={total_accepted} rejected={total_rejected} "
        f"elapsed={elapsed:.1f}s"
    )

    # Final adversarial summary.
    rows = list(jsonlines.open(out)) if out.exists() else []
    adversarial = [r for r in rows if r.get("source") == "adversarial"]
    pair_ids = {r.get("pair_id") for r in adversarial if r.get("pair_id")}
    regression_rows = [r for r in adversarial if r.get("split") == "regression"]
    pairs_with_regression = {
        r.get("pair_id") for r in regression_rows if r.get("pair_id")
    }
    table = Table(title="Adversarial-pair coverage")
    table.add_column("Field")
    table.add_column("Value", justify="right")
    table.add_row("adversarial examples", str(len(adversarial)))
    table.add_row("unique pair_ids", str(len(pair_ids)))
    table.add_row(
        "pairs with ≥1 regression example",
        f"{len(pairs_with_regression)} / {len(pair_ids)}",
    )
    table.add_row(
        "regression-split adversarial rows", str(len(regression_rows))
    )
    _console.print(table)


# =============================================================================
# CLI
# =============================================================================


@click.group()
def main() -> None:
    """Adversarial pair expansion driver for M3 Phase C."""


@main.command()
@click.option(
    "--out",
    type=click.Path(dir_okay=False, path_type=Path),
    default=Path("../datasets/preflight/preflight_v0.1.0.jsonl"),
)
@click.option(
    "--variants-per-side",
    default=5,
    type=int,
    help="Rephrasings per prototype side. 5 → ~880 examples across 80 pairs.",
)
@click.option("--model", default=DEFAULT_OLLAMA_MODEL)
@click.option("--max-tokens", default=4096, type=int)
@click.option("--seed", default=None, type=int)
@click.option(
    "--only",
    "only_pair_id",
    default=None,
    help="If set, only expand this pair_id (useful for re-runs).",
)
def preflight(
    out: Path,
    variants_per_side: int,
    model: str,
    max_tokens: int,
    seed: int | None,
    only_pair_id: str | None,
) -> None:
    """Expand the 80 hand-authored pre-flight pair prototypes."""
    _console.print(
        f"[bold]ct-expand-pairs preflight[/bold] backend={_backend()} "
        f"model={_resolve_model(model)} variants_per_side={variants_per_side} "
        f"out={out}"
    )
    _drive_preflight_pairs(
        out=out,
        variants_per_side=variants_per_side,
        model=model,
        max_tokens=max_tokens,
        seed=seed,
        only_pair_id=only_pair_id,
    )


# =============================================================================
# Memory expansion
# =============================================================================


def _resolve_expiration(expiration: str | None) -> str | None:
    """Replace __TODAY_PLUS_N__ placeholders with concrete ISO dates."""
    if expiration is None:
        return None
    from datetime import date, timedelta
    today = date.today()
    if expiration == "__TODAY_PLUS_7__":
        return (today + timedelta(days=7)).isoformat()
    if expiration == "__TODAY_PLUS_30__":
        return (today + timedelta(days=30)).isoformat()
    return expiration


def _fact_to_dict(f: MemoryFact) -> dict[str, Any]:
    out: dict[str, Any] = {
        "text": f.text,
        "category": f.category,
        "stability": f.stability,
        "confidence": f.confidence,
    }
    exp = _resolve_expiration(f.expiration_iso_date)
    if exp is not None:
        out["expiration_iso_date"] = exp
    return out


def _next_memory_id_idx(rows: list[dict[str, Any]]) -> int:
    max_seen = -1
    for r in rows:
        rid = r.get("id", "")
        if rid.startswith("memory_"):
            try:
                n = int(rid[len("memory_"):])
                if n > max_seen:
                    max_seen = n
            except ValueError:
                continue
    return max_seen + 1


def _expand_memory_side(
    pair: MemoryProtoPair,
    side: MemoryProto,
    variants_per_side: int,
    model: str,
    max_tokens: int,
) -> list[dict[str, Any]]:
    memories_json = _json.dumps(
        [_fact_to_dict(m) for m in side.memories_to_extract]
    )
    negatives_json = _json.dumps(
        [_fact_to_dict(m) for m in side.negative_extractions]
    )
    prompt = _render_prompt(
        "memory_pair_expansion.j2",
        count=variants_per_side,
        prototype_user_message=side.user_message,
        prototype_assistant_response=side.assistant_response,
        prototype_memories_json=memories_json,
        prototype_negatives_json=negatives_json,
        prototype_rationale=side.rationale,
        explicit_command=side.explicit_command,
        pair_type=pair.pair_type,
    )
    raw = _call_backend(prompt, model=model, max_tokens=max_tokens)
    return _parse_array(raw)


def _materialize_memory_examples(
    pair: MemoryProtoPair,
    side: MemoryProto,
    raw_variants: list[dict[str, Any]],
    next_idx: int,
    existing_canon: set[str],
    rng: random.Random,
) -> tuple[list[MemoryExtractionExample], list[tuple[str, str]], int]:
    accepted: list[MemoryExtractionExample] = []
    rejected: list[tuple[str, str]] = []
    seen = set(existing_canon)
    forced_one = False  # first kept goes to regression

    candidates: list[tuple[str, str]] = [
        (side.user_message, side.assistant_response)
    ]
    for v in raw_variants:
        um = v.get("user_message")
        ar = v.get("assistant_response", "")
        if isinstance(um, str) and um.strip():
            candidates.append((um.strip(), ar.strip() if isinstance(ar, str) else side.assistant_response))

    memories = [_fact_to_dict(m) for m in side.memories_to_extract]
    negatives = [_fact_to_dict(m) for m in side.negative_extractions]

    for um, ar in candidates:
        canon = _canonicalize(um)
        if not canon or canon in seen:
            rejected.append((um, "duplicate"))
            continue
        seen.add(canon)
        if not forced_one:
            split = SplitName.REGRESSION.value
            forced_one = True
        else:
            split = rng.choices(
                ["train", "val", "test"], weights=[0.78, 0.13, 0.09], k=1
            )[0]
        row: dict[str, Any] = {
            "id": f"memory_{next_idx:05d}",
            "user_message": um,
            "assistant_response": ar or "Got it.",
            "memories_to_extract": memories,
            "negative_extractions": negatives,
            "rationale": side.rationale,
            "pair_id": pair.pair_id,
            "source": Source.ADVERSARIAL.value,
            "split": split,
        }
        if side.explicit_command is not None:
            row["explicit_command"] = side.explicit_command
        try:
            example = MemoryExtractionExample(**row)
        except ValidationError as e:
            rejected.append((um, f"schema: {e.errors()[0]['msg'] if e.errors() else e}"))
            continue
        accepted.append(example)
        next_idx += 1
    return accepted, rejected, next_idx


def _drive_memory_pairs(
    out: Path,
    variants_per_side: int,
    model: str,
    max_tokens: int,
    seed: int | None,
    only_pair_id: str | None,
) -> None:
    rng = random.Random(seed)
    rows = list(jsonlines.open(out)) if out.exists() else []
    by_pair: Counter[str] = Counter()
    for r in rows:
        pid = r.get("pair_id")
        if pid:
            by_pair[pid] += 1
    next_idx = _next_memory_id_idx(rows)
    existing_canon = _existing_canonicals(rows)

    pairs = ALL_MEMORY_PROTOTYPES
    if only_pair_id:
        pairs = tuple(p for p in pairs if p.pair_id == only_pair_id)
        if not pairs:
            raise click.ClickException(
                f"No memory prototype with pair_id={only_pair_id!r}"
            )

    target_per_side = variants_per_side + 1

    started = time.monotonic()
    pairs_done = 0
    pairs_skipped = 0
    total_accepted = 0
    total_rejected = 0

    for pair in pairs:
        wanted = target_per_side * len(pair.sides)
        if by_pair.get(pair.pair_id, 0) >= wanted:
            pairs_skipped += 1
            continue

        for side_idx, side in enumerate(pair.sides):
            try:
                raw = _expand_memory_side(
                    pair=pair,
                    side=side,
                    variants_per_side=variants_per_side,
                    model=model,
                    max_tokens=max_tokens,
                )
            except Exception as e:  # noqa: BLE001
                _console.print(
                    f"[red]Expansion failed for {pair.pair_id}/{side.user_message[:40]}: {e}[/red]"
                )
                continue
            accepted, rejected, next_idx = _materialize_memory_examples(
                pair=pair,
                side=side,
                raw_variants=raw,
                next_idx=next_idx,
                existing_canon=existing_canon,
                rng=rng,
            )
            for ex in accepted:
                existing_canon.add(_canonicalize(ex.user_message))
            _append_jsonl(out, accepted)
            total_accepted += len(accepted)
            total_rejected += len(rejected)
            _console.print(
                f"  pair={pair.pair_id} side[{side_idx}] kept={len(accepted)} "
                f"rej={len(rejected)}"
            )
        pairs_done += 1

    elapsed = time.monotonic() - started
    _console.print(
        f"\n[bold]Done.[/bold] pairs_done={pairs_done} skipped={pairs_skipped} "
        f"accepted={total_accepted} rejected={total_rejected} "
        f"elapsed={elapsed:.1f}s"
    )

    rows = list(jsonlines.open(out)) if out.exists() else []
    adversarial = [r for r in rows if r.get("source") == "adversarial"]
    pair_ids = {r.get("pair_id") for r in adversarial if r.get("pair_id")}
    regression_rows = [r for r in adversarial if r.get("split") == "regression"]
    pairs_with_reg = {r.get("pair_id") for r in regression_rows if r.get("pair_id")}
    table = Table(title="Memory adversarial coverage")
    table.add_column("Field")
    table.add_column("Value", justify="right")
    table.add_row("adversarial examples", str(len(adversarial)))
    table.add_row("unique pair_ids", str(len(pair_ids)))
    table.add_row(
        "pairs with ≥1 regression example",
        f"{len(pairs_with_reg)} / {len(pair_ids)}",
    )
    table.add_row("regression-split adversarial rows", str(len(regression_rows)))
    _console.print(table)


@main.command()
@click.option(
    "--out",
    type=click.Path(dir_okay=False, path_type=Path),
    default=Path("../datasets/memory/memory_v0.1.0.jsonl"),
)
@click.option(
    "--variants-per-side",
    default=5,
    type=int,
    help="Rephrasings per prototype side.",
)
@click.option("--model", default=DEFAULT_OLLAMA_MODEL)
@click.option("--max-tokens", default=4096, type=int)
@click.option("--seed", default=None, type=int)
@click.option(
    "--only",
    "only_pair_id",
    default=None,
    help="If set, only expand this pair_id.",
)
def memory(
    out: Path,
    variants_per_side: int,
    model: str,
    max_tokens: int,
    seed: int | None,
    only_pair_id: str | None,
) -> None:
    """Expand the hand-authored memory hard-case prototypes (Phase C §3.5)."""
    _console.print(
        f"[bold]ct-expand-pairs memory[/bold] backend={_backend()} "
        f"model={_resolve_model(model)} variants_per_side={variants_per_side} "
        f"out={out}"
    )
    _drive_memory_pairs(
        out=out,
        variants_per_side=variants_per_side,
        model=model,
        max_tokens=max_tokens,
        seed=seed,
        only_pair_id=only_pair_id,
    )


if __name__ == "__main__":
    main()
