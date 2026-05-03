"""Synthetic generation pipeline for both classifiers.

Reads a Jinja2 prompt template, calls a frontier model (Claude by default; configurable
via env), parses the returned JSON array, validates each example against the relevant
Pydantic schema, assigns IDs and split labels, and appends to a JSONL file.

Designed to be run in batches with `--count` examples per call. Reject rates of
30–40% on the first pass are expected per CLASSIFIER_DATASETS.md §2.6 — accepted
examples are written; rejects are logged with their validation error.
"""

from __future__ import annotations

import json
import os
import random
import sys
from collections.abc import Iterable
from pathlib import Path
from typing import Any

import click
import jsonlines
from jinja2 import Environment, FileSystemLoader, select_autoescape
from pydantic import ValidationError
from rich.console import Console
from tenacity import retry, stop_after_attempt, wait_exponential

from ..datasets.schemas import (
    MemoryExtractionExample,
    PreflightExample,
    Source,
    SplitName,
)

_console = Console()

PROMPTS_DIR = Path(__file__).resolve().parents[3] / "prompts"

# -----------------------------------------------------------------------------
# Frontier-model client
# -----------------------------------------------------------------------------


@retry(stop=stop_after_attempt(3), wait=wait_exponential(min=2, max=20))
def _call_claude(prompt: str, model: str, max_tokens: int) -> str:
    """Calls Anthropic's API. Lazy import so the labeling-only install (no anthropic
    dep) doesn't crash."""
    try:
        from anthropic import Anthropic
    except ImportError as e:
        raise click.ClickException(
            "anthropic package required for synthetic generation. "
            "Install with: pip install -e '.[dev]'"
        ) from e

    api_key = os.environ.get("ANTHROPIC_API_KEY")
    if not api_key:
        raise click.ClickException("ANTHROPIC_API_KEY env var is not set")
    client = Anthropic(api_key=api_key)
    response = client.messages.create(
        model=model,
        max_tokens=max_tokens,
        messages=[{"role": "user", "content": prompt}],
    )
    # Concatenate text blocks. The prompt instructs the model to output a JSON array only.
    parts: list[str] = []
    for block in response.content:
        if getattr(block, "type", None) == "text":
            parts.append(block.text)
    return "".join(parts).strip()


def _render_prompt(template_name: str, **vars: Any) -> str:
    env = Environment(
        loader=FileSystemLoader(str(PROMPTS_DIR)),
        autoescape=select_autoescape(default=False),
        trim_blocks=True,
        lstrip_blocks=True,
    )
    template = env.get_template(template_name)
    return template.render(**vars)


def _parse_array(raw: str) -> list[dict[str, Any]]:
    """Defensive JSON-array parser. Strips Markdown code fences if the model added them."""
    text = raw.strip()
    if text.startswith("```"):
        # Drop opening fence (```json or just ```).
        text = text.split("\n", 1)[1] if "\n" in text else text[3:]
        if text.endswith("```"):
            text = text[:-3]
    text = text.strip()
    parsed = json.loads(text)
    if not isinstance(parsed, list):
        raise ValueError(f"expected a JSON array, got {type(parsed).__name__}")
    return parsed


# -----------------------------------------------------------------------------
# Split assignment
# -----------------------------------------------------------------------------


_SPLIT_WEIGHTS = {
    SplitName.TRAIN: 0.75,
    SplitName.VAL: 0.125,
    SplitName.TEST: 0.085,
    SplitName.REGRESSION: 0.04,  # frozen post-launch; only used for early seed rounds
}


def _assign_split(rng: random.Random) -> SplitName:
    r = rng.random()
    cumulative = 0.0
    for split, weight in _SPLIT_WEIGHTS.items():
        cumulative += weight
        if r < cumulative:
            return split
    return SplitName.TRAIN


# -----------------------------------------------------------------------------
# Pre-flight generation
# -----------------------------------------------------------------------------


def _next_id(out_path: Path, prefix: str) -> int:
    """Returns the next 0-padded id index by scanning existing JSONL."""
    if not out_path.exists():
        return 0
    max_seen = -1
    with jsonlines.open(out_path) as reader:
        for row in reader:
            rid = row.get("id", "")
            if rid.startswith(prefix):
                try:
                    n = int(rid[len(prefix):])
                    if n > max_seen:
                        max_seen = n
                except ValueError:
                    pass
    return max_seen + 1


def _generate_preflight_batch(
    count: int,
    target_label: str,
    target_categories: list[str],
    prior_examples: list[dict[str, Any]],
    model: str,
    max_tokens: int,
) -> list[dict[str, Any]]:
    prompt = _render_prompt(
        "preflight_generation.j2",
        count=count,
        target_label=target_label,
        target_categories=target_categories,
        prior_examples=prior_examples,
    )
    raw = _call_claude(prompt, model=model, max_tokens=max_tokens)
    return _parse_array(raw)


def _validate_and_assign(
    raw_examples: Iterable[dict[str, Any]],
    schema: type,
    out_path: Path,
    id_prefix: str,
    rng: random.Random,
    source: Source,
) -> tuple[list[Any], list[tuple[dict[str, Any], str]]]:
    """Returns (accepted_examples, rejected_with_errors)."""
    accepted: list[Any] = []
    rejected: list[tuple[dict[str, Any], str]] = []
    next_idx = _next_id(out_path, id_prefix)
    for raw in raw_examples:
        raw.setdefault("source", source.value)
        raw.setdefault("split", _assign_split(rng).value)
        raw["id"] = f"{id_prefix}{next_idx:05d}"
        try:
            example = schema(**raw)
            accepted.append(example)
            next_idx += 1
        except ValidationError as e:
            rejected.append((raw, str(e)))
    return accepted, rejected


def _append_jsonl(out_path: Path, examples: list[Any]) -> None:
    out_path.parent.mkdir(parents=True, exist_ok=True)
    with jsonlines.open(out_path, mode="a") as writer:
        for ex in examples:
            writer.write(ex.model_dump(mode="json", exclude_none=True))


# -----------------------------------------------------------------------------
# CLIs
# -----------------------------------------------------------------------------


@click.command()
@click.option("--count", type=int, default=20, help="Examples per generation call.")
@click.option(
    "--target-label",
    type=click.Choice(["search_required", "search_not_required", "ambiguous", "mixed"]),
    default="mixed",
)
@click.option(
    "--target-category",
    "target_categories",
    multiple=True,
    help="Bias toward these categories. Repeatable.",
)
@click.option(
    "--out",
    type=click.Path(dir_okay=False, path_type=Path),
    default=Path("../datasets/preflight/preflight_v0.1.0.jsonl"),
)
@click.option("--model", default="claude-opus-4-7", help="Anthropic model id.")
@click.option("--max-tokens", default=8192, type=int)
@click.option("--seed", default=None, type=int)
def preflight_cli(
    count: int,
    target_label: str,
    target_categories: tuple[str, ...],
    out: Path,
    model: str,
    max_tokens: int,
    seed: int | None,
) -> None:
    """Generate a batch of pre-flight classifier examples and append to JSONL."""
    rng = random.Random(seed)
    prior = _sample_prior(out, prefix="preflight_", k=3, rng=rng)
    raw = _generate_preflight_batch(
        count=count,
        target_label=target_label,
        target_categories=list(target_categories),
        prior_examples=prior,
        model=model,
        max_tokens=max_tokens,
    )
    accepted, rejected = _validate_and_assign(
        raw_examples=raw,
        schema=PreflightExample,
        out_path=out,
        id_prefix="preflight_",
        rng=rng,
        source=Source.SYNTHETIC_V1,
    )
    _append_jsonl(out, accepted)
    _console.print(
        f"[green]Accepted {len(accepted)}[/green] / "
        f"[yellow]Rejected {len(rejected)}[/yellow] → {out}"
    )
    for raw_ex, err in rejected[:5]:
        _console.print(f"[red]Rejected[/red] {raw_ex.get('query', '?')!r}: {err}")


@click.command()
@click.option("--count", type=int, default=20)
@click.option(
    "--target-density",
    type=click.Choice(["empty", "one", "multi", "mixed"]),
    default="mixed",
)
@click.option(
    "--hard-case",
    type=click.Choice([
        "temporary_vs_stable",
        "sensitive",
        "forget_command",
        "remember_command",
        "implicit_vs_explicit_preference",
    ]),
    default=None,
)
@click.option(
    "--out",
    type=click.Path(dir_okay=False, path_type=Path),
    default=Path("../datasets/memory/memory_v0.1.0.jsonl"),
)
@click.option("--model", default="claude-opus-4-7")
@click.option("--max-tokens", default=8192, type=int)
@click.option("--seed", default=None, type=int)
def memory_cli(
    count: int,
    target_density: str,
    hard_case: str | None,
    out: Path,
    model: str,
    max_tokens: int,
    seed: int | None,
) -> None:
    """Generate a batch of memory-extraction classifier examples."""
    rng = random.Random(seed)
    prior = _sample_prior(out, prefix="memory_", k=3, rng=rng)
    prompt = _render_prompt(
        "memory_generation.j2",
        count=count,
        target_density=target_density,
        hard_case=hard_case,
        prior_examples=prior,
    )
    raw = _call_claude(prompt, model=model, max_tokens=max_tokens)
    parsed = _parse_array(raw)
    accepted, rejected = _validate_and_assign(
        raw_examples=parsed,
        schema=MemoryExtractionExample,
        out_path=out,
        id_prefix="memory_",
        rng=rng,
        source=Source.SYNTHETIC_V1,
    )
    _append_jsonl(out, accepted)
    _console.print(
        f"[green]Accepted {len(accepted)}[/green] / "
        f"[yellow]Rejected {len(rejected)}[/yellow] → {out}"
    )
    for raw_ex, err in rejected[:5]:
        _console.print(f"[red]Rejected[/red] {raw_ex.get('id', '?')}: {err}")


def _sample_prior(
    path: Path, prefix: str, k: int, rng: random.Random
) -> list[dict[str, Any]]:
    """Reservoir sample of accepted prior examples for in-context guidance."""
    if not path.exists():
        return []
    selected: list[dict[str, Any]] = []
    with jsonlines.open(path) as reader:
        for i, row in enumerate(reader):
            if not row.get("id", "").startswith(prefix):
                continue
            if i < k:
                selected.append(row)
            else:
                j = rng.randint(0, i)
                if j < k:
                    selected[j] = row
    return selected


if __name__ == "__main__":
    sys.exit("Use ct-generate-preflight or ct-generate-memory entry points.")
