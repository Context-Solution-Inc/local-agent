"""Sets up Argilla workspaces and datasets for the two classifiers.

Argilla is the labeling tool selected in M0 (PHASE1_PLAN.md WS-5/6). It supports:
  - JSONL import/export with custom schemas
  - Two-labeler agreement (Cohen's kappa) per CLASSIFIER_DATASETS.md §2.7
  - Adjudication workflow when labelers disagree

This module is idempotent — running `ct-argilla-init` on an existing workspace
adds missing datasets but does not overwrite labeled data.

Requires the [labeling] extras: pip install -e '.[labeling]'
"""

from __future__ import annotations

import os
import sys

import click
from rich.console import Console

_console = Console()

WORKSPACE_DEFAULT = "mobile-agent"
PREFLIGHT_DATASET = "preflight-classifier"
MEMORY_DATASET = "memory-extractor"


def _client():
    """Lazy-import Argilla so the labeling-only install isn't required for other workflows."""
    try:
        import argilla as rg
    except ImportError as e:
        raise click.ClickException(
            "argilla is required. Install with: pip install -e '.[labeling]'"
        ) from e

    api_url = os.environ.get("ARGILLA_API_URL")
    api_key = os.environ.get("ARGILLA_API_KEY")
    if not (api_url and api_key):
        raise click.ClickException(
            "Set ARGILLA_API_URL and ARGILLA_API_KEY env vars before running."
        )
    return rg.Argilla(api_url=api_url, api_key=api_key)


def _ensure_workspace(client, name: str):
    import argilla as rg

    existing = client.workspaces(name=name)
    if existing is not None:
        _console.print(f"Workspace [cyan]{name}[/cyan] already exists.")
        return existing
    workspace = rg.Workspace(name=name)
    workspace.create(client=client)
    _console.print(f"[green]Created workspace[/green] {name}")
    return workspace


def _ensure_preflight_dataset(client, workspace_name: str) -> None:
    import argilla as rg

    if client.datasets(name=PREFLIGHT_DATASET, workspace=workspace_name) is not None:
        _console.print(f"Dataset [cyan]{PREFLIGHT_DATASET}[/cyan] already exists.")
        return

    settings = rg.Settings(
        guidelines=(
            "Label each query as search_required, search_not_required, or ambiguous. "
            "See CLASSIFIER_DATASETS.md §2 for full guidelines and adversarial pair "
            "definitions. When in doubt, prefer ambiguous over a wrong high-confidence label."
        ),
        fields=[
            rg.TextField(name="query", title="User query", use_markdown=False),
            rg.TextField(name="rationale_synth", title="Synthetic rationale", required=False),
        ],
        questions=[
            rg.LabelQuestion(
                name="label",
                title="Does this query require a fresh web search?",
                labels=["search_required", "search_not_required", "ambiguous"],
            ),
            rg.LabelQuestion(
                name="confidence",
                title="Confidence",
                labels=["high", "medium", "low"],
            ),
            rg.LabelQuestion(
                name="category",
                title="Category",
                labels=[
                    "sports_recent", "sports_upcoming", "markets_current", "weather",
                    "news_current", "prices_products", "status_recent", "schedules_events",
                    "general_knowledge", "settled_history", "opinion_reasoning",
                    "coding_math", "creative", "personal_memory", "meta", "ambiguous",
                ],
            ),
            rg.TextQuestion(
                name="rationale",
                title="Rationale (1-2 sentences)",
                required=True,
            ),
        ],
        metadata=[
            rg.TermsMetadataProperty(name="source"),
            rg.TermsMetadataProperty(name="split"),
            rg.TermsMetadataProperty(name="pair_id"),
        ],
    )
    dataset = rg.Dataset(
        name=PREFLIGHT_DATASET,
        workspace=workspace_name,
        settings=settings,
    )
    dataset.create(client=client)
    _console.print(f"[green]Created dataset[/green] {PREFLIGHT_DATASET}")


def _ensure_memory_dataset(client, workspace_name: str) -> None:
    import argilla as rg

    if client.datasets(name=MEMORY_DATASET, workspace=workspace_name) is not None:
        _console.print(f"Dataset [cyan]{MEMORY_DATASET}[/cyan] already exists.")
        return

    settings = rg.Settings(
        guidelines=(
            "For each user/assistant exchange, identify any DURABLE facts about the user "
            "that should be saved as memory. Include negative extractions (things you might "
            "be tempted to extract but shouldn't). See CLASSIFIER_DATASETS.md §3 for the "
            "full taxonomy and edge cases."
        ),
        fields=[
            rg.TextField(name="user_message", title="User message", use_markdown=False),
            rg.TextField(name="assistant_response", title="Assistant response", use_markdown=False),
        ],
        questions=[
            rg.TextQuestion(
                name="memories_to_extract_json",
                title="Memories to extract (JSON array, possibly empty)",
                required=True,
            ),
            rg.TextQuestion(
                name="negative_extractions_json",
                title="Negative extractions (JSON array, possibly empty)",
                required=False,
            ),
            rg.LabelQuestion(
                name="explicit_command",
                title="Explicit command",
                labels=["none", "remember", "forget"],
            ),
            rg.TextQuestion(
                name="rationale",
                title="Rationale",
                required=True,
            ),
        ],
        metadata=[
            rg.TermsMetadataProperty(name="source"),
            rg.TermsMetadataProperty(name="split"),
        ],
    )
    dataset = rg.Dataset(
        name=MEMORY_DATASET,
        workspace=workspace_name,
        settings=settings,
    )
    dataset.create(client=client)
    _console.print(f"[green]Created dataset[/green] {MEMORY_DATASET}")


@click.command()
@click.option("--workspace", default=WORKSPACE_DEFAULT, help="Argilla workspace name.")
def main(workspace: str) -> None:
    """Idempotently create the labeling workspace and both classifier datasets."""
    client = _client()
    _ensure_workspace(client, workspace)
    _ensure_preflight_dataset(client, workspace)
    _ensure_memory_dataset(client, workspace)
    _console.print("[bold green]Argilla setup complete.[/bold green]")


if __name__ == "__main__":
    main()
    sys.exit(0)
