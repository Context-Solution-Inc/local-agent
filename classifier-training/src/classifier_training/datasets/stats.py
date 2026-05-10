"""Distribution dashboard for the two classifier datasets.

Run via `ct-stats <path-to-jsonl>`. Auto-detects pre-flight vs memory based on
the id prefix of the first row. Prints current counts vs the §2.2 / §3.2
targets in CLASSIFIER_DATASETS.md and flags gaps that should drive the next
generation batch.

This is the "what should I generate next?" tool used during M3 Phase B.
"""

from __future__ import annotations

import re
from collections import Counter
from collections.abc import Iterable
from pathlib import Path

import click
import jsonlines
from rich.console import Console
from rich.table import Table

from .schemas import (
    MemoryCategory,
    MemoryExtractionExample,
    PreflightCategory,
    PreflightConfidence,
    PreflightExample,
    PreflightLabel,
    SplitName,
    Source,
)

_console = Console()


# =============================================================================
# Targets per CLASSIFIER_DATASETS.md
# =============================================================================

# §2.2 — total 12,000; train/val/test/regression = 9,000/1,500/1,000/500
PREFLIGHT_TOTAL_TARGET = 12_000
PREFLIGHT_SPLIT_TARGETS = {
    SplitName.TRAIN: 9_000,
    SplitName.VAL: 1_500,
    SplitName.TEST: 1_000,
    SplitName.REGRESSION: 500,
}
# §2.2 label proportions
PREFLIGHT_LABEL_PROPORTIONS = {
    PreflightLabel.SEARCH_REQUIRED: 0.40,
    PreflightLabel.SEARCH_NOT_REQUIRED: 0.45,
    PreflightLabel.AMBIGUOUS: 0.15,
}
# §2.2 confidence proportions WITHIN each non-ambiguous label
PREFLIGHT_CONFIDENCE_PROPORTIONS = {
    PreflightConfidence.HIGH: 0.70,
    PreflightConfidence.MEDIUM: 0.25,
    PreflightConfidence.LOW: 0.05,
}
# §2.4 — at least 800 EXAMPLES (≈7% of the dataset) must come in pairs.
PREFLIGHT_ADVERSARIAL_EXAMPLES_TARGET = 800
# §2.5 ≥30% naturalistic phrasings.
PREFLIGHT_NATURALISTIC_TARGET_PCT = 30.0

# §3.2 — total 8,000; train/val/test/regression = 6,000/1,000/600/400
MEMORY_TOTAL_TARGET = 8_000
MEMORY_SPLIT_TARGETS = {
    SplitName.TRAIN: 6_000,
    SplitName.VAL: 1_000,
    SplitName.TEST: 600,
    SplitName.REGRESSION: 400,
}
# §3.2 density proportions
MEMORY_DENSITY_PROPORTIONS = {
    "empty": 0.60,
    "one": 0.28,
    "multi": 0.12,
}
# §3.5 hard-case minimums
MEMORY_FORGET_TARGET = 200
MEMORY_REMEMBER_TARGET = 200


# =============================================================================
# Naturalistic-phrasing heuristic (§2.5)
# =============================================================================
#
# We need a stable signal — not a perfect detector — to track whether the
# dataset stays above the ≥30% naturalistic-phrasing target. The earlier
# 2-of-5 heuristic flagged 89.6% of synthetic queries as naturalistic
# because "lowercase start" and "no end punctuation" both fire on most
# generator output regardless of register. The revised heuristic separates
# STRONG signals (any one ⇒ naturalistic) from WEAK signals (need ≥2):

# Strong: informal abbreviations or apostrophe-dropped contractions.
_INFORMAL_HINTS = re.compile(
    r"\b("
    r"rn|tho|imo|fr|ngl|tbh|dunno|gonna|wanna|kinda|sorta|ya|"
    r"yeah|nah|wtf|btw|lol|asap|"
    r"whats|hows|wheres|whens|isnt|dont|cant|wont|aint|im|youre|theyre|"
    r"guys|stuff|thing"
    r")\b",
    re.IGNORECASE,
)

# Strong: conversational openers — explicit "I'm thinking out loud" framing.
_CONVERSATIONAL_OPENER = re.compile(
    r"^(hey|yo|wait|so |like |ok so |i was wondering|i wonder|man |dude)\b",
    re.IGNORECASE,
)


def _is_naturalistic(query: str) -> bool:
    """Conservative under-counter for naturalistic phrasings.

    Synthetic generators tend to emit lowercase-no-punct strings by default,
    so surface features like "no end punctuation" are uncorrelated with
    register and a poor signal here. We instead key off content cues:
    informal vocabulary, conversational openers, and short fragments that
    start with an interrogative verb but lack a formal "what is / how do"
    structure.

    Designed to UNDER-count rather than over-count — a ≥30% reading is a
    real signal that the naturalistic-share target is being met.
    """
    q = query.strip()
    if not q:
        return False
    # Strong: informal abbreviations or apostrophe-dropped contractions.
    if _INFORMAL_HINTS.search(q):
        return True
    # Strong: conversational opener.
    if _CONVERSATIONAL_OPENER.search(q):
        return True
    # Strong: rambling conversational filler — long with multiple commas.
    words = q.split()
    if len(words) >= 14 and q.count(",") >= 2:
        return True
    # Medium: short fragment starting with an interrogative verb but
    # lacking a formal "wh- + be/do" structure. Discriminates
    # "did the eagles win" (naturalistic) from
    # "what is the population of Brazil" (search-engine style).
    if len(words) <= 8 and re.match(
        r"^(did|is|are|was|were|got|do|does|got|gonna|any|whats|hows)\b",
        q,
        re.IGNORECASE,
    ):
        # Reject if the phrasing is actually a formal "did the X do Y"
        # interrogative (e.g. "did Lincoln free the slaves") — those are
        # dictionary-style settled history queries, not naturalistic.
        if not re.search(
            r"\b(the|a|an)\s+(\w+\s+){0,3}(do|does|did|have|has|cause|caused|invent|invented|free|sign|win)\b",
            q,
            re.IGNORECASE,
        ):
            return True
    return False


# =============================================================================
# Loading
# =============================================================================


def _detect_kind(path: Path) -> str:
    with jsonlines.open(path) as reader:
        for row in reader:
            rid = row.get("id", "")
            if rid.startswith("preflight_"):
                return "preflight"
            if rid.startswith("memory_"):
                return "memory"
            raise click.ClickException(
                f"first record has unrecognized id '{rid}' "
                "(expected 'preflight_*' or 'memory_*')"
            )
    raise click.ClickException(f"{path} is empty")


def _load_preflight(path: Path) -> list[PreflightExample]:
    out: list[PreflightExample] = []
    with jsonlines.open(path) as reader:
        for row in reader:
            out.append(PreflightExample(**row))
    return out


def _load_memory(path: Path) -> list[MemoryExtractionExample]:
    out: list[MemoryExtractionExample] = []
    with jsonlines.open(path) as reader:
        for row in reader:
            out.append(MemoryExtractionExample(**row))
    return out


# =============================================================================
# Render helpers
# =============================================================================


def _delta_str(have: int, want: int) -> str:
    if want <= 0:
        return ""
    if have >= want:
        return f"[green]✓ +{have - want}[/green]"
    return f"[yellow]−{want - have}[/yellow]"


def _pct(n: int, total: int) -> str:
    if total <= 0:
        return "—"
    return f"{(n / total) * 100:.1f}%"


def _gap_pct(have_pct: float, want_pct: float, tolerance: float = 2.0) -> str:
    diff = have_pct - want_pct
    if abs(diff) <= tolerance:
        return f"[green]Δ{diff:+.1f}pp[/green]"
    if diff < 0:
        return f"[yellow]Δ{diff:+.1f}pp[/yellow]"
    return f"[cyan]Δ{diff:+.1f}pp[/cyan]"


# =============================================================================
# Preflight summary
# =============================================================================


def _summarize_preflight(examples: list[PreflightExample]) -> None:
    total = len(examples)
    _console.print(
        f"\n[bold]Pre-flight dataset[/bold] — {total} examples "
        f"(target {PREFLIGHT_TOTAL_TARGET}, "
        f"{_delta_str(total, PREFLIGHT_TOTAL_TARGET)})"
    )

    # Splits ----------------------------------------------------------------
    splits: Counter[SplitName] = Counter(e.split for e in examples)
    t = Table(title="Splits", show_header=True)
    t.add_column("Split")
    t.add_column("Count", justify="right")
    t.add_column("Target", justify="right")
    t.add_column("Status")
    for split, target in PREFLIGHT_SPLIT_TARGETS.items():
        c = splits.get(split, 0)
        t.add_row(split.value, str(c), str(target), _delta_str(c, target))
    _console.print(t)

    # Labels ----------------------------------------------------------------
    labels: Counter[PreflightLabel] = Counter(e.label for e in examples)
    t = Table(title="Labels (target proportions per §2.2)", show_header=True)
    t.add_column("Label")
    t.add_column("Count", justify="right")
    t.add_column("Have %", justify="right")
    t.add_column("Want %", justify="right")
    t.add_column("Status")
    for label, want_pct in PREFLIGHT_LABEL_PROPORTIONS.items():
        c = labels.get(label, 0)
        have_pct = (c / total * 100) if total else 0.0
        t.add_row(
            label.value, str(c), f"{have_pct:.1f}%", f"{want_pct * 100:.0f}%",
            _gap_pct(have_pct, want_pct * 100),
        )
    _console.print(t)

    # Confidence within search_required + search_not_required ---------------
    t = Table(title="Confidence within decisive labels (§2.2)", show_header=True)
    t.add_column("Confidence")
    t.add_column("Count", justify="right")
    t.add_column("Have %", justify="right")
    t.add_column("Want %", justify="right")
    t.add_column("Status")
    decisive = [
        e for e in examples
        if e.label in (PreflightLabel.SEARCH_REQUIRED, PreflightLabel.SEARCH_NOT_REQUIRED)
    ]
    decisive_total = len(decisive) or 1
    confs: Counter[PreflightConfidence] = Counter(e.confidence for e in decisive)
    for conf, want in PREFLIGHT_CONFIDENCE_PROPORTIONS.items():
        c = confs.get(conf, 0)
        have_pct = c / decisive_total * 100
        t.add_row(
            conf.value, str(c), f"{have_pct:.1f}%", f"{want * 100:.0f}%",
            _gap_pct(have_pct, want * 100),
        )
    _console.print(t)

    # Categories ------------------------------------------------------------
    cats: Counter[PreflightCategory] = Counter(e.category for e in examples)
    t = Table(title="Categories", show_header=True)
    t.add_column("Category")
    t.add_column("Count", justify="right")
    t.add_column("% of total", justify="right")
    for cat in PreflightCategory:
        c = cats.get(cat, 0)
        t.add_row(cat.value, str(c), _pct(c, total))
    _console.print(t)

    # Adversarial pairs (§2.4) ----------------------------------------------
    pair_ids = {e.pair_id for e in examples if e.pair_id}
    pair_examples = [e for e in examples if e.pair_id]
    _console.print(
        f"\n[bold]Adversarial pairs (§2.4, target ≥{PREFLIGHT_ADVERSARIAL_EXAMPLES_TARGET} examples):[/bold] "
        f"{len(pair_examples)} examples across {len(pair_ids)} pair_ids "
        f"{_delta_str(len(pair_examples), PREFLIGHT_ADVERSARIAL_EXAMPLES_TARGET)}"
    )

    # Naturalistic share (§2.5) ---------------------------------------------
    natural = sum(1 for e in examples if _is_naturalistic(e.query))
    natural_pct = (natural / total * 100) if total else 0.0
    _console.print(
        f"[bold]Naturalistic phrasings (§2.5, target ≥{PREFLIGHT_NATURALISTIC_TARGET_PCT:.0f}%):[/bold] "
        f"{natural} ({natural_pct:.1f}%) "
        f"{_gap_pct(natural_pct, PREFLIGHT_NATURALISTIC_TARGET_PCT, tolerance=2.0)}"
    )

    # Source mix ------------------------------------------------------------
    sources: Counter[Source] = Counter(e.source for e in examples)
    src_summary = ", ".join(f"{s.value}={n}" for s, n in sources.most_common())
    _console.print(f"[dim]Sources: {src_summary}[/dim]")

    # "Generate next" hints -------------------------------------------------
    _print_preflight_next_hints(examples, total)


def _print_preflight_next_hints(
    examples: list[PreflightExample], total: int
) -> None:
    """Print the most underweight buckets so the user knows what to generate next."""
    if total == 0:
        _console.print("[bold cyan]Next:[/bold cyan] generate any examples to get started.")
        return
    hints: list[str] = []
    labels: Counter[PreflightLabel] = Counter(e.label for e in examples)
    # Label gap
    biggest_label_gap: tuple[float, PreflightLabel | None] = (0.0, None)
    for label, want_pct in PREFLIGHT_LABEL_PROPORTIONS.items():
        have = labels.get(label, 0)
        want = int(round(PREFLIGHT_TOTAL_TARGET * want_pct))
        gap_share = (want - have) / max(want, 1)
        if gap_share > biggest_label_gap[0]:
            biggest_label_gap = (gap_share, label)
    if biggest_label_gap[1] is not None:
        hints.append(
            f"--target-label {biggest_label_gap[1].value} "
            f"(short by {biggest_label_gap[0] * 100:.0f}%)"
        )
    # Naturalistic share
    natural_pct = (sum(1 for e in examples if _is_naturalistic(e.query)) / total) * 100
    if natural_pct < PREFLIGHT_NATURALISTIC_TARGET_PCT:
        hints.append(
            f"increase naturalistic phrasings (currently {natural_pct:.1f}% < "
            f"{PREFLIGHT_NATURALISTIC_TARGET_PCT:.0f}%)"
        )
    # Adversarial pairs
    pair_examples = sum(1 for e in examples if e.pair_id)
    if pair_examples < PREFLIGHT_ADVERSARIAL_EXAMPLES_TARGET:
        hints.append(
            f"expand adversarial pairs ({pair_examples} / "
            f"{PREFLIGHT_ADVERSARIAL_EXAMPLES_TARGET} examples)"
        )
    if hints:
        _console.print("\n[bold cyan]Next batch should target:[/bold cyan]")
        for h in hints:
            _console.print(f"  • {h}")


# =============================================================================
# Memory summary
# =============================================================================


def _summarize_memory(examples: list[MemoryExtractionExample]) -> None:
    total = len(examples)
    _console.print(
        f"\n[bold]Memory dataset[/bold] — {total} examples "
        f"(target {MEMORY_TOTAL_TARGET}, "
        f"{_delta_str(total, MEMORY_TOTAL_TARGET)})"
    )

    # Splits
    splits: Counter[SplitName] = Counter(e.split for e in examples)
    t = Table(title="Splits", show_header=True)
    t.add_column("Split")
    t.add_column("Count", justify="right")
    t.add_column("Target", justify="right")
    t.add_column("Status")
    for split, target in MEMORY_SPLIT_TARGETS.items():
        c = splits.get(split, 0)
        t.add_row(split.value, str(c), str(target), _delta_str(c, target))
    _console.print(t)

    # Density
    empty_n = sum(1 for e in examples if not e.memories_to_extract)
    one_n = sum(1 for e in examples if len(e.memories_to_extract) == 1)
    multi_n = sum(1 for e in examples if len(e.memories_to_extract) >= 2)
    t = Table(title="Memory density (target proportions per §3.2)", show_header=True)
    t.add_column("Density")
    t.add_column("Count", justify="right")
    t.add_column("Have %", justify="right")
    t.add_column("Want %", justify="right")
    t.add_column("Status")
    for label, count_n, want in (
        ("empty", empty_n, MEMORY_DENSITY_PROPORTIONS["empty"]),
        ("one", one_n, MEMORY_DENSITY_PROPORTIONS["one"]),
        ("multi", multi_n, MEMORY_DENSITY_PROPORTIONS["multi"]),
    ):
        have_pct = (count_n / total * 100) if total else 0.0
        t.add_row(
            label, str(count_n), f"{have_pct:.1f}%", f"{want * 100:.0f}%",
            _gap_pct(have_pct, want * 100, tolerance=3.0),
        )
    _console.print(t)

    # Memory categories (across all extracted memories, not turns)
    cat_counts: Counter[MemoryCategory] = Counter()
    for e in examples:
        for m in e.memories_to_extract:
            cat_counts[m.category] += 1
    t = Table(title="Memory categories (across all extracted memories)", show_header=True)
    t.add_column("Category")
    t.add_column("Count", justify="right")
    extracted_total = sum(cat_counts.values()) or 1
    for cat in MemoryCategory:
        c = cat_counts.get(cat, 0)
        t.add_row(cat.value, str(c) + f"  ({c / extracted_total * 100:.1f}%)")
    _console.print(t)

    # Hard cases
    forget_n = sum(1 for e in examples if e.explicit_command == "forget")
    remember_n = sum(1 for e in examples if e.explicit_command == "remember")
    _console.print(
        f"\n[bold]Explicit commands (§3.5):[/bold] "
        f"forget={forget_n}/{MEMORY_FORGET_TARGET} "
        f"{_delta_str(forget_n, MEMORY_FORGET_TARGET)}, "
        f"remember={remember_n}/{MEMORY_REMEMBER_TARGET} "
        f"{_delta_str(remember_n, MEMORY_REMEMBER_TARGET)}"
    )

    # Negative-extraction coverage
    with_negatives = sum(1 for e in examples if e.negative_extractions)
    _console.print(
        f"[bold]Hard negatives:[/bold] {with_negatives} examples carry at least "
        f"one negative extraction ({_pct(with_negatives, total)})"
    )

    # Source mix
    sources: Counter[Source] = Counter(e.source for e in examples)
    src_summary = ", ".join(f"{s.value}={n}" for s, n in sources.most_common())
    _console.print(f"[dim]Sources: {src_summary}[/dim]")

    # "Generate next" hints
    _print_memory_next_hints(empty_n, one_n, multi_n, total, forget_n, remember_n)


def _print_memory_next_hints(
    empty_n: int, one_n: int, multi_n: int, total: int,
    forget_n: int, remember_n: int,
) -> None:
    if total == 0:
        _console.print("[bold cyan]Next:[/bold cyan] generate any examples to get started.")
        return
    hints: list[str] = []
    target_empty = int(MEMORY_TOTAL_TARGET * MEMORY_DENSITY_PROPORTIONS["empty"])
    target_one = int(MEMORY_TOTAL_TARGET * MEMORY_DENSITY_PROPORTIONS["one"])
    target_multi = int(MEMORY_TOTAL_TARGET * MEMORY_DENSITY_PROPORTIONS["multi"])
    if empty_n < target_empty:
        hints.append(f"--target-density empty (have {empty_n} / {target_empty})")
    if one_n < target_one:
        hints.append(f"--target-density one (have {one_n} / {target_one})")
    if multi_n < target_multi:
        hints.append(f"--target-density multi (have {multi_n} / {target_multi})")
    if forget_n < MEMORY_FORGET_TARGET:
        hints.append(
            f"--hard-case forget_command "
            f"(have {forget_n} / {MEMORY_FORGET_TARGET})"
        )
    if remember_n < MEMORY_REMEMBER_TARGET:
        hints.append(
            f"--hard-case remember_command "
            f"(have {remember_n} / {MEMORY_REMEMBER_TARGET})"
        )
    if hints:
        _console.print("\n[bold cyan]Next batch should target:[/bold cyan]")
        for h in hints:
            _console.print(f"  • {h}")


# =============================================================================
# CLI
# =============================================================================


def _md_pct(n: int, total: int) -> str:
    if total <= 0:
        return "—"
    return f"{(n / total) * 100:.1f}%"


def _md_status(have: int, want: int) -> str:
    if want <= 0:
        return ""
    if have >= want:
        return f"✓ +{have - want}"
    return f"−{want - have}"


def _md_summarize_preflight(examples: list[PreflightExample]) -> str:
    """Emit Markdown-formatted distribution tables for committing into MANIFEST.md."""
    total = len(examples)
    out: list[str] = []
    out.append(f"## Pre-flight distribution — {total} examples (target {PREFLIGHT_TOTAL_TARGET})\n")

    # Splits
    out.append("### Splits\n")
    out.append("| Split | Count | Target | Status |")
    out.append("|---|---:|---:|---|")
    splits: Counter[SplitName] = Counter(e.split for e in examples)
    for split, target in PREFLIGHT_SPLIT_TARGETS.items():
        c = splits.get(split, 0)
        out.append(f"| {split.value} | {c} | {target} | {_md_status(c, target)} |")
    out.append("")

    # Labels
    out.append("### Labels (§2.2 target proportions)\n")
    out.append("| Label | Count | Have % | Want % |")
    out.append("|---|---:|---:|---:|")
    labels: Counter[PreflightLabel] = Counter(e.label for e in examples)
    for label, want_pct in PREFLIGHT_LABEL_PROPORTIONS.items():
        c = labels.get(label, 0)
        out.append(f"| {label.value} | {c} | {_md_pct(c, total)} | {want_pct * 100:.0f}% |")
    out.append("")

    # Confidence
    out.append("### Confidence (within decisive labels, §2.2)\n")
    out.append("| Confidence | Count | Have % | Want % |")
    out.append("|---|---:|---:|---:|")
    decisive = [
        e for e in examples
        if e.label in (PreflightLabel.SEARCH_REQUIRED, PreflightLabel.SEARCH_NOT_REQUIRED)
    ]
    decisive_total = len(decisive) or 1
    confs: Counter[PreflightConfidence] = Counter(e.confidence for e in decisive)
    for conf, want in PREFLIGHT_CONFIDENCE_PROPORTIONS.items():
        c = confs.get(conf, 0)
        out.append(f"| {conf.value} | {c} | {_md_pct(c, decisive_total)} | {want * 100:.0f}% |")
    out.append("")

    # Categories
    out.append("### Categories\n")
    out.append("| Category | Count | % of total |")
    out.append("|---|---:|---:|")
    cats: Counter[PreflightCategory] = Counter(e.category for e in examples)
    for cat in PreflightCategory:
        c = cats.get(cat, 0)
        out.append(f"| {cat.value} | {c} | {_md_pct(c, total)} |")
    out.append("")

    # Adversarial pairs (§2.4)
    pair_ids = {e.pair_id for e in examples if e.pair_id}
    pair_examples = [e for e in examples if e.pair_id]
    out.append("### Adversarial pairs (§2.4)\n")
    out.append(
        f"- {len(pair_examples)} examples across {len(pair_ids)} pair_ids "
        f"(target ≥{PREFLIGHT_ADVERSARIAL_EXAMPLES_TARGET} examples) "
        f"{_md_status(len(pair_examples), PREFLIGHT_ADVERSARIAL_EXAMPLES_TARGET)}"
    )
    out.append("")

    # Naturalistic
    natural = sum(1 for e in examples if _is_naturalistic(e.query))
    natural_pct = (natural / total * 100) if total else 0.0
    out.append("### Naturalistic phrasings (§2.5)\n")
    out.append(
        f"- {natural} ({natural_pct:.1f}%) "
        f"(target ≥{PREFLIGHT_NATURALISTIC_TARGET_PCT:.0f}%)"
    )
    out.append("")

    # Sources
    sources: Counter[Source] = Counter(e.source for e in examples)
    out.append("### Sources\n")
    out.append("| Source | Count |")
    out.append("|---|---:|")
    for s, n in sources.most_common():
        out.append(f"| {s.value} | {n} |")
    out.append("")

    return "\n".join(out)


def _md_summarize_memory(examples: list[MemoryExtractionExample]) -> str:
    """Emit Markdown-formatted distribution tables for committing into MANIFEST.md."""
    total = len(examples)
    out: list[str] = []
    out.append(f"## Memory distribution — {total} examples (target {MEMORY_TOTAL_TARGET})\n")

    # Splits
    out.append("### Splits\n")
    out.append("| Split | Count | Target | Status |")
    out.append("|---|---:|---:|---|")
    splits: Counter[SplitName] = Counter(e.split for e in examples)
    for split, target in MEMORY_SPLIT_TARGETS.items():
        c = splits.get(split, 0)
        out.append(f"| {split.value} | {c} | {target} | {_md_status(c, target)} |")
    out.append("")

    # Density
    empty_n = sum(1 for e in examples if not e.memories_to_extract)
    one_n = sum(1 for e in examples if len(e.memories_to_extract) == 1)
    multi_n = sum(1 for e in examples if len(e.memories_to_extract) >= 2)
    out.append("### Memory density (§3.2 target proportions)\n")
    out.append("| Density | Count | Have % | Want % |")
    out.append("|---|---:|---:|---:|")
    for label, count_n, want in (
        ("empty", empty_n, MEMORY_DENSITY_PROPORTIONS["empty"]),
        ("one", one_n, MEMORY_DENSITY_PROPORTIONS["one"]),
        ("multi", multi_n, MEMORY_DENSITY_PROPORTIONS["multi"]),
    ):
        out.append(
            f"| {label} | {count_n} | {_md_pct(count_n, total)} | {want * 100:.0f}% |"
        )
    out.append("")

    # Memory categories
    cat_counts: Counter[MemoryCategory] = Counter()
    for e in examples:
        for m in e.memories_to_extract:
            cat_counts[m.category] += 1
    extracted_total = sum(cat_counts.values()) or 1
    out.append("### Memory categories (across all extracted memories)\n")
    out.append("| Category | Count | % |")
    out.append("|---|---:|---:|")
    for cat in MemoryCategory:
        c = cat_counts.get(cat, 0)
        out.append(f"| {cat.value} | {c} | {(c / extracted_total * 100):.1f}% |")
    out.append("")

    # Hard cases (§3.5)
    forget_n = sum(1 for e in examples if e.explicit_command == "forget")
    remember_n = sum(1 for e in examples if e.explicit_command == "remember")
    with_negatives = sum(1 for e in examples if e.negative_extractions)
    out.append("### Hard-case coverage (§3.5)\n")
    out.append("| Field | Count | Target | Status |")
    out.append("|---|---:|---:|---|")
    out.append(f"| explicit forget | {forget_n} | ≥{MEMORY_FORGET_TARGET} | {_md_status(forget_n, MEMORY_FORGET_TARGET)} |")
    out.append(f"| explicit remember | {remember_n} | ≥{MEMORY_REMEMBER_TARGET} | {_md_status(remember_n, MEMORY_REMEMBER_TARGET)} |")
    out.append(f"| with negative_extractions | {with_negatives} | — | {_md_pct(with_negatives, total)} |")
    out.append("")

    # Adversarial pairs (Phase C)
    pair_ids = {e.pair_id for e in examples if e.pair_id}
    pair_examples = [e for e in examples if e.pair_id]
    out.append("### Adversarial hard-case pairs (Phase C)\n")
    out.append(
        f"- {len(pair_examples)} examples across {len(pair_ids)} pair_ids"
    )
    out.append("")

    # Sources
    sources: Counter[Source] = Counter(e.source for e in examples)
    out.append("### Sources\n")
    out.append("| Source | Count |")
    out.append("|---|---:|")
    for s, n in sources.most_common():
        out.append(f"| {s.value} | {n} |")
    out.append("")

    return "\n".join(out)


@click.command()
@click.argument("path", type=click.Path(exists=True, dir_okay=False, path_type=Path))
@click.option(
    "--markdown", is_flag=True, default=False,
    help="Emit Markdown tables suitable for embedding in MANIFEST.md.",
)
def main(path: Path, markdown: bool) -> None:
    """Print distribution stats for a classifier JSONL dataset.

    Auto-detects pre-flight vs memory based on the id prefix of the first row.
    Default output is rich-rendered tables; pass --markdown for plain
    Markdown suitable for committing into a manifest.
    """
    kind = _detect_kind(path)
    if kind == "preflight":
        examples = _load_preflight(path)
        if markdown:
            print(_md_summarize_preflight(examples))
        else:
            _summarize_preflight(examples)
    else:
        examples = _load_memory(path)
        if markdown:
            print(_md_summarize_memory(examples))
        else:
            _summarize_memory(examples)


if __name__ == "__main__":
    main()
