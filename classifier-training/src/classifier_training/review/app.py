"""Local single-user JSONL review tool.

Usage:
    ct-review datasets/preflight/preflight_v0.1.0.jsonl
    ct-review memory.jsonl --filter category=temporary_context
    ct-review preflight.jsonl --filter label=ambiguous --filter source=adversarial

Per-example keys:
    a   accept
    r   reject (then prompt for one-line reason)
    e   edit in $EDITOR (validates against the Pydantic schema on save)
    s   skip (decision deferred — example will reappear on next run)
    q   quit (writes accepted / rejected files and exits)
    ?   show key reference

State is persisted to a sidecar `<input>.review.jsonl`. Re-running the tool
picks up where you left off — already-decided rows are not shown again. On
exit (q or last example), `<input>.accepted.jsonl` and `<input>.rejected.jsonl`
are emitted with the final decisions.
"""

from __future__ import annotations

import json
import os
import subprocess
import sys
import tempfile
from collections.abc import Callable
from pathlib import Path
from typing import Any

import click
import jsonlines
from pydantic import BaseModel, ValidationError
from rich.console import Console
from rich.json import JSON
from rich.panel import Panel
from rich.text import Text

from ..datasets.schemas import MemoryExtractionExample, PreflightExample

_console = Console()


def _detect_schema(rows: list[dict[str, Any]]) -> type[BaseModel]:
    if not rows:
        raise click.ClickException("Dataset is empty.")
    first_id = rows[0].get("id", "")
    if first_id.startswith("preflight_"):
        return PreflightExample
    if first_id.startswith("memory_"):
        return MemoryExtractionExample
    raise click.ClickException(
        f"Cannot detect schema from id {first_id!r} (expected preflight_* or memory_*)."
    )


def _load_jsonl(path: Path) -> list[dict[str, Any]]:
    with jsonlines.open(path) as reader:
        return list(reader)


def _load_decisions(review_path: Path) -> dict[str, dict[str, Any]]:
    """Returns {id: decision_record}. The latest decision per id wins."""
    if not review_path.exists():
        return {}
    out: dict[str, dict[str, Any]] = {}
    with jsonlines.open(review_path) as reader:
        for row in reader:
            rid = row.get("id")
            if rid:
                out[rid] = row
    return out


def _append_decision(review_path: Path, decision: dict[str, Any]) -> None:
    review_path.parent.mkdir(parents=True, exist_ok=True)
    with jsonlines.open(review_path, mode="a") as writer:
        writer.write(decision)


def _matches_filter(row: dict[str, Any], filters: list[tuple[str, str]]) -> bool:
    for key, want in filters:
        have = row.get(key)
        if have is None or str(have) != want:
            return False
    return True


def _render_example(idx: int, total: int, row: dict[str, Any]) -> None:
    _console.clear()
    header = Text(
        f"[{idx + 1}/{total}]  id={row.get('id', '?')}",
        style="bold cyan",
    )
    _console.print(header)
    _console.print(Panel(JSON(json.dumps(row, ensure_ascii=False)), expand=True))


def _prompt_key(prompt: str = "[a]ccept  [r]eject  [e]dit  [s]kip  [q]uit  [?]") -> str:
    line = input(f"\n{prompt}\n> ").strip().lower()
    return line[:1] if line else ""


def _prompt_reason() -> str:
    return input("Reject reason (one line): ").strip()


def _edit_in_editor(row: dict[str, Any], schema: type[BaseModel]) -> dict[str, Any] | None:
    """Returns the edited row, or None if the user bailed.

    Loops until the edited JSON validates against the schema or the user gives up.
    """
    editor = os.environ.get("EDITOR") or os.environ.get("VISUAL") or "nano"
    snippet = json.dumps(row, indent=2, ensure_ascii=False)
    while True:
        with tempfile.NamedTemporaryFile(
            mode="w", suffix=".json", delete=False, encoding="utf-8"
        ) as tf:
            tf.write(snippet)
            tmp_path = Path(tf.name)
        try:
            subprocess.run([editor, str(tmp_path)], check=False)
            edited_text = tmp_path.read_text(encoding="utf-8")
        finally:
            tmp_path.unlink(missing_ok=True)
        try:
            edited = json.loads(edited_text)
        except json.JSONDecodeError as e:
            _console.print(f"[red]Invalid JSON: {e}[/red]")
            choice = input("[r]e-edit  [a]bort edit > ").strip().lower()[:1]
            if choice == "a":
                return None
            snippet = edited_text
            continue
        try:
            schema(**edited)
        except ValidationError as e:
            _console.print(f"[red]Schema validation failed:[/red]\n{e}")
            choice = input("[r]e-edit  [a]bort edit > ").strip().lower()[:1]
            if choice == "a":
                return None
            snippet = json.dumps(edited, indent=2, ensure_ascii=False)
            continue
        return edited


def _emit_accepted_rejected(
    rows: list[dict[str, Any]],
    decisions: dict[str, dict[str, Any]],
    base: Path,
) -> tuple[int, int]:
    """Writes <base>.accepted.jsonl and <base>.rejected.jsonl from the union of
    original rows + per-id decisions. Edits override the original row content.
    Returns (accepted_count, rejected_count)."""
    accepted_path = base.with_suffix(base.suffix + ".accepted.jsonl")
    rejected_path = base.with_suffix(base.suffix + ".rejected.jsonl")

    # Truncate before writing.
    accepted_path.write_text("")
    rejected_path.write_text("")

    accepted = 0
    rejected = 0
    by_id = {r.get("id"): r for r in rows}
    with jsonlines.open(accepted_path, mode="a") as awriter, jsonlines.open(
        rejected_path, mode="a"
    ) as rwriter:
        for rid, dec in decisions.items():
            verdict = dec.get("verdict")
            row = by_id.get(rid)
            if row is None:
                continue
            if verdict == "accept":
                # Use the edited payload if present.
                final = dec.get("edited_row") or row
                awriter.write(final)
                accepted += 1
            elif verdict == "reject":
                rejected_row = dict(row)
                rejected_row["_reject_reason"] = dec.get("reason", "")
                rwriter.write(rejected_row)
                rejected += 1
    return accepted, rejected


@click.command()
@click.argument("path", type=click.Path(exists=True, dir_okay=False, path_type=Path))
@click.option(
    "--filter",
    "filters",
    multiple=True,
    help="key=value filters (repeatable). e.g. --filter category=sports_recent --filter label=ambiguous",
)
@click.option(
    "--review-file",
    type=click.Path(dir_okay=False, path_type=Path),
    default=None,
    help="Sidecar decisions file (default: <path>.review.jsonl).",
)
@click.option(
    "--include-decided",
    is_flag=True,
    default=False,
    help="Show rows that already have a decision (use to revisit accepted/rejected).",
)
def main(
    path: Path,
    filters: tuple[str, ...],
    review_file: Path | None,
    include_decided: bool,
) -> None:
    """Solo accept/reject/edit review loop for synthetic JSONL examples."""
    rows = _load_jsonl(path)
    schema = _detect_schema(rows)
    if review_file is None:
        review_file = path.with_suffix(path.suffix + ".review.jsonl")
    decisions = _load_decisions(review_file)

    parsed_filters: list[tuple[str, str]] = []
    for f in filters:
        if "=" not in f:
            raise click.ClickException(f"--filter must be key=value, got {f!r}")
        k, v = f.split("=", 1)
        parsed_filters.append((k.strip(), v.strip()))

    todo = [
        r for r in rows
        if _matches_filter(r, parsed_filters)
        and (include_decided or r.get("id") not in decisions)
    ]

    _console.print(
        f"[bold]ct-review[/bold] {path} → {len(rows)} rows, "
        f"{len(decisions)} prior decisions, {len(todo)} pending."
    )
    if parsed_filters:
        _console.print(f"[dim]Filters: {parsed_filters}[/dim]")
    if not todo:
        _console.print("[green]Nothing to review.[/green] Emitting accepted/rejected files.")
        a, r = _emit_accepted_rejected(rows, decisions, path)
        _console.print(f"  accepted={a}  rejected={r}")
        return

    quit_requested = False
    for idx, row in enumerate(todo):
        _render_example(idx, len(todo), row)
        while True:
            key = _prompt_key()
            if key == "?":
                _console.print(
                    "[a]ccept  [r]eject  [e]dit  [s]kip  [q]uit  [?]help",
                )
                continue
            if key == "q":
                quit_requested = True
                break
            if key == "s":
                break
            if key == "a":
                dec = {"id": row.get("id"), "verdict": "accept"}
                _append_decision(review_file, dec)
                decisions[row["id"]] = dec
                break
            if key == "r":
                reason = _prompt_reason()
                dec = {
                    "id": row.get("id"),
                    "verdict": "reject",
                    "reason": reason,
                }
                _append_decision(review_file, dec)
                decisions[row["id"]] = dec
                break
            if key == "e":
                edited = _edit_in_editor(row, schema)
                if edited is None:
                    continue  # back to the same example
                dec = {
                    "id": row.get("id"),
                    "verdict": "accept",
                    "edited_row": edited,
                }
                _append_decision(review_file, dec)
                decisions[row["id"]] = dec
                break
            _console.print(f"[red]Unknown key {key!r}. Press ? for help.[/red]")
        if quit_requested:
            break

    a, r = _emit_accepted_rejected(rows, decisions, path)
    remaining = sum(1 for d in decisions.values() if d.get("verdict") == "skip")
    _console.print(
        f"\n[bold]Session done.[/bold] accepted={a} rejected={r}  → "
        f"{path.with_suffix(path.suffix + '.accepted.jsonl')}, "
        f"{path.with_suffix(path.suffix + '.rejected.jsonl')}"
    )


if __name__ == "__main__":
    main()
