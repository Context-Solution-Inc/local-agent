"""Deduplication for classifier datasets.

Two passes:

1. **Canonical exact match.** Lowercase, strip punctuation, collapse whitespace.
   Drop rows whose canonical form matches an earlier row.
2. **Near-duplicate via sentence embeddings.** all-MiniLM-L6-v2 cosine.
   Drop rows whose embedding has cosine similarity > THRESHOLD (default 0.92)
   to ANY earlier row's embedding within the same `category` (preflight) or
   density bucket (memory).

Run via `ct-dedup <path>` with `--dry-run` to preview, `--apply` to rewrite
the file. The original file is backed up to `<path>.prededuped.jsonl` when
applying.

Embedding model is loaded lazily — first run downloads ~25 MB to the HF cache.
"""

from __future__ import annotations

import re
import shutil
from collections import defaultdict
from collections.abc import Iterable
from pathlib import Path
from typing import Any

import click
import jsonlines
from rich.console import Console
from rich.table import Table

_console = Console()

DEFAULT_NEAR_DUP_THRESHOLD = 0.92
DEFAULT_EMBEDDER_MODEL = "sentence-transformers/all-MiniLM-L6-v2"


# =============================================================================
# Canonical form
# =============================================================================


_PUNCT = re.compile(r"[^\w\s]")
_WS = re.compile(r"\s+")


def _canonicalize(text: str) -> str:
    t = text.lower()
    t = _PUNCT.sub(" ", t)
    t = _WS.sub(" ", t).strip()
    return t


# =============================================================================
# Bucket key (per-bucket near-dup search keeps cost manageable)
# =============================================================================


def _bucket_key(row: dict[str, Any]) -> str:
    """Group rows so near-dup search only runs within a bucket."""
    if row.get("id", "").startswith("preflight_"):
        return f"preflight:{row.get('category', 'unknown')}"
    if row.get("id", "").startswith("memory_"):
        n = len(row.get("memories_to_extract") or [])
        density = "empty" if n == 0 else "one" if n == 1 else "multi"
        return f"memory:{density}"
    return "unknown"


def _text_for_embedding(row: dict[str, Any]) -> str:
    """The text we hash / embed for dedup. For preflight: the query. For
    memory: the user_message."""
    if row.get("id", "").startswith("preflight_"):
        return row.get("query", "")
    if row.get("id", "").startswith("memory_"):
        return row.get("user_message", "")
    raise ValueError(f"Cannot extract dedup text from row id={row.get('id')!r}")


# =============================================================================
# Pass 1 — canonical exact-match
# =============================================================================


def _exact_dedup(rows: list[dict[str, Any]]) -> tuple[list[dict[str, Any]], list[tuple[str, str]]]:
    """Returns (kept, dropped). `dropped` items are (id, reason) tuples."""
    seen: dict[str, str] = {}
    kept: list[dict[str, Any]] = []
    dropped: list[tuple[str, str]] = []
    for r in rows:
        text = _text_for_embedding(r)
        key = _canonicalize(text)
        if not key:
            kept.append(r)
            continue
        if key in seen:
            dropped.append(
                (r.get("id", "?"), f"exact dup of {seen[key]} (canonical='{key[:60]}')")
            )
            continue
        seen[key] = r.get("id", "?")
        kept.append(r)
    return kept, dropped


# =============================================================================
# Pass 2 — near-dup via embeddings
# =============================================================================


def _near_dedup(
    rows: list[dict[str, Any]], threshold: float, model_name: str
) -> tuple[list[dict[str, Any]], list[tuple[str, str]]]:
    """Cosine-based near-dup, bucketed by category/density."""
    try:
        from sentence_transformers import SentenceTransformer, util
    except ImportError as e:
        raise click.ClickException(
            "sentence-transformers required for near-dup. "
            "Install with: pip install -e '.[dedup]'"
        ) from e
    import numpy as np
    import torch

    if not rows:
        return rows, []

    _console.print(f"[dim]Loading {model_name}...[/dim]")
    model = SentenceTransformer(model_name)

    # Bucket rows then embed each bucket together.
    buckets: dict[str, list[int]] = defaultdict(list)
    for i, r in enumerate(rows):
        buckets[_bucket_key(r)].append(i)

    drop_indices: set[int] = set()
    drop_reasons: list[tuple[str, str]] = []

    for bucket, indices in buckets.items():
        if len(indices) < 2:
            continue
        texts = [_text_for_embedding(rows[i]) for i in indices]
        emb = model.encode(
            texts,
            convert_to_tensor=True,
            normalize_embeddings=True,
            show_progress_bar=False,
        )
        # Cosine via dot product on normalized vectors.
        sim = (emb @ emb.T).cpu().numpy()
        np.fill_diagonal(sim, 0.0)
        # Greedy: walk in order, drop a row if it has any prior similarity > threshold.
        for local_i, global_i in enumerate(indices):
            if global_i in drop_indices:
                continue
            # Look only at earlier (kept) rows for matches above threshold.
            for local_j in range(local_i):
                global_j = indices[local_j]
                if global_j in drop_indices:
                    continue
                if sim[local_i, local_j] >= threshold:
                    drop_indices.add(global_i)
                    drop_reasons.append(
                        (
                            rows[global_i].get("id", "?"),
                            f"near-dup of {rows[global_j].get('id', '?')} "
                            f"in bucket={bucket} (sim={sim[local_i, local_j]:.3f})",
                        )
                    )
                    break

    kept = [r for i, r in enumerate(rows) if i not in drop_indices]
    return kept, drop_reasons


# =============================================================================
# CLI
# =============================================================================


@click.command()
@click.argument("path", type=click.Path(exists=True, dir_okay=False, path_type=Path))
@click.option("--apply", "apply_changes", is_flag=True, default=False, help="Rewrite the file (with backup).")
@click.option("--dry-run", is_flag=True, default=False, help="Show what would be dropped (default unless --apply).")
@click.option("--threshold", type=float, default=DEFAULT_NEAR_DUP_THRESHOLD, help="Cosine similarity threshold for near-dup.")
@click.option("--model", default=DEFAULT_EMBEDDER_MODEL, help="Sentence-Transformers model id.")
@click.option("--skip-near", is_flag=True, default=False, help="Skip pass 2 (only run exact-match dedup).")
def main(
    path: Path,
    apply_changes: bool,
    dry_run: bool,
    threshold: float,
    model: str,
    skip_near: bool,
) -> None:
    """Dedup a classifier dataset JSONL.

    Default is dry-run. Use --apply to rewrite the file (backup written).
    """
    if not apply_changes:
        dry_run = True
    with jsonlines.open(path) as reader:
        rows = list(reader)
    _console.print(f"[bold]Input:[/bold] {len(rows)} rows in {path}")

    kept, exact_drops = _exact_dedup(rows)
    if skip_near:
        near_drops: list[tuple[str, str]] = []
    else:
        kept, near_drops = _near_dedup(kept, threshold=threshold, model_name=model)

    table = Table(title="ct-dedup summary")
    table.add_column("Pass")
    table.add_column("Dropped", justify="right")
    table.add_row("1. canonical exact-match", str(len(exact_drops)))
    table.add_row("2. near-dup (cos ≥ %.2f)" % threshold, str(len(near_drops)))
    table.add_row("[bold]kept[/bold]", f"[bold]{len(kept)}[/bold]")
    _console.print(table)

    sample = (exact_drops + near_drops)[:10]
    if sample:
        _console.print("\n[dim]Sample drops:[/dim]")
        for rid, reason in sample:
            _console.print(f"  {rid}: {reason}")

    if dry_run:
        _console.print("\n[yellow]Dry run.[/yellow] Pass --apply to rewrite the file.")
        return

    backup = path.with_suffix(path.suffix + ".prededuped.jsonl")
    shutil.copy(path, backup)
    with jsonlines.open(path, mode="w") as writer:
        for r in kept:
            writer.write(r)
    _console.print(f"\n[green]Wrote[/green] {len(kept)} rows to {path}; backup at {backup}")


if __name__ == "__main__":
    main()
