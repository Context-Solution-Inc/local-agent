"""JSONL validators. Run via `ct-validate <path>` or in CI on every dataset commit."""

from __future__ import annotations

import sys
from collections import Counter
from collections.abc import Iterable
from pathlib import Path

import click
import jsonlines
from pydantic import ValidationError
from rich.console import Console
from rich.table import Table

from .schemas import (
    MemoryExtractionExample,
    PreflightExample,
    PreflightLabel,
    SplitName,
)

_console = Console()


def _detect_kind(path: Path) -> str:
    """Determine which schema applies based on the first record's id prefix."""
    with jsonlines.open(path) as reader:
        for first in reader:
            record_id = first.get("id", "")
            if record_id.startswith("preflight_"):
                return "preflight"
            if record_id.startswith("memory_"):
                return "memory"
            raise click.ClickException(
                f"first record has unrecognized id '{record_id}' "
                "(expected 'preflight_*' or 'memory_*')"
            )
    raise click.ClickException(f"{path} is empty")


def _iter_validated_preflight(path: Path) -> Iterable[PreflightExample]:
    with jsonlines.open(path) as reader:
        for line_num, raw in enumerate(reader, start=1):
            try:
                yield PreflightExample(**raw)
            except ValidationError as e:
                raise click.ClickException(
                    f"line {line_num} (id={raw.get('id', '?')}): {e}"
                ) from None


def _iter_validated_memory(path: Path) -> Iterable[MemoryExtractionExample]:
    with jsonlines.open(path) as reader:
        for line_num, raw in enumerate(reader, start=1):
            try:
                yield MemoryExtractionExample(**raw)
            except ValidationError as e:
                raise click.ClickException(
                    f"line {line_num} (id={raw.get('id', '?')}): {e}"
                ) from None


def _summarize_preflight(examples: list[PreflightExample]) -> None:
    splits: Counter[SplitName] = Counter(e.split for e in examples)
    labels: Counter[PreflightLabel] = Counter(e.label for e in examples)

    table = Table(title="Pre-flight dataset summary", show_header=True)
    table.add_column("Field")
    table.add_column("Count", justify="right")
    table.add_column("% of total", justify="right")
    total = len(examples)
    for split in SplitName:
        c = splits[split]
        table.add_row(f"split={split.value}", str(c), f"{(c / total * 100):.1f}%" if total else "—")
    for label in PreflightLabel:
        c = labels[label]
        table.add_row(f"label={label.value}", str(c), f"{(c / total * 100):.1f}%" if total else "—")
    _console.print(table)

    # Adversarial pair coverage check.
    pair_ids = {e.pair_id for e in examples if e.pair_id}
    if pair_ids:
        _console.print(f"[green]Adversarial pairs: {len(pair_ids)} unique pair_ids[/green]")


def _summarize_memory(examples: list[MemoryExtractionExample]) -> None:
    splits: Counter[SplitName] = Counter(e.split for e in examples)
    empty_extraction = sum(1 for e in examples if not e.memories_to_extract)
    one_extraction = sum(1 for e in examples if len(e.memories_to_extract) == 1)
    multi_extraction = sum(1 for e in examples if len(e.memories_to_extract) >= 2)
    explicit_remember = sum(1 for e in examples if e.explicit_command == "remember")
    explicit_forget = sum(1 for e in examples if e.explicit_command == "forget")

    total = len(examples) or 1
    table = Table(title="Memory extraction dataset summary", show_header=True)
    table.add_column("Field")
    table.add_column("Count", justify="right")
    table.add_column("% of total", justify="right")
    for split in SplitName:
        c = splits[split]
        table.add_row(f"split={split.value}", str(c), f"{(c / total * 100):.1f}%")
    table.add_row("0 memories", str(empty_extraction), f"{(empty_extraction / total * 100):.1f}%")
    table.add_row("1 memory", str(one_extraction), f"{(one_extraction / total * 100):.1f}%")
    table.add_row("2+ memories", str(multi_extraction), f"{(multi_extraction / total * 100):.1f}%")
    table.add_row("explicit remember", str(explicit_remember), f"{(explicit_remember / total * 100):.1f}%")
    table.add_row("explicit forget", str(explicit_forget), f"{(explicit_forget / total * 100):.1f}%")
    _console.print(table)


@click.command()
@click.argument("path", type=click.Path(exists=True, dir_okay=False, path_type=Path))
@click.option(
    "--summary/--no-summary",
    default=True,
    help="Print a per-split / per-label distribution summary.",
)
def main(path: Path, summary: bool) -> None:
    """Validate a JSONL dataset file against the schema.

    Detects pre-flight vs memory based on the id prefix of the first record.
    Exits non-zero on the first validation failure so CI can gate commits.
    """
    kind = _detect_kind(path)
    _console.print(f"[bold]Validating {path} as {kind} dataset…[/bold]")
    if kind == "preflight":
        examples = list(_iter_validated_preflight(path))
        _console.print(f"[green]✓ {len(examples)} examples passed schema validation[/green]")
        if summary:
            _summarize_preflight(examples)
    else:
        examples = list(_iter_validated_memory(path))
        _console.print(f"[green]✓ {len(examples)} examples passed schema validation[/green]")
        if summary:
            _summarize_memory(examples)


if __name__ == "__main__":
    main()
    sys.exit(0)
