"""Torch Datasets, tokenization, and label encoding for both classifier tasks.

The shared encoder ingests text:
  - preflight: just the user `query`
  - memory:    `user_message` + [SEP] + `assistant_response`

Labels:
  - preflight: 3-class (search_required / search_not_required / ambiguous)
  - memory presence: binary (≥1 memory to extract)
  - memory category: multi-label over 6 MemoryCategory enum values

`MultiTaskBatcher` interleaves preflight and memory batches so each gradient
step covers both tasks proportionally.
"""

from __future__ import annotations

from collections.abc import Iterable
from dataclasses import dataclass
from pathlib import Path
from typing import Any

import jsonlines
import torch
from torch.utils.data import Dataset

from ..datasets.schemas import (
    MemoryCategory,
    MemoryExtractionExample,
    PreflightExample,
    PreflightLabel,
    SplitName,
)


# =============================================================================
# Label encoders
# =============================================================================


PREFLIGHT_LABEL_TO_IDX: dict[str, int] = {
    PreflightLabel.SEARCH_REQUIRED.value: 0,
    PreflightLabel.SEARCH_NOT_REQUIRED.value: 1,
    PreflightLabel.AMBIGUOUS.value: 2,
}
PREFLIGHT_IDX_TO_LABEL: dict[int, str] = {v: k for k, v in PREFLIGHT_LABEL_TO_IDX.items()}

MEMORY_CATEGORIES: list[str] = [c.value for c in MemoryCategory]
MEMORY_CATEGORY_TO_IDX: dict[str, int] = {c: i for i, c in enumerate(MEMORY_CATEGORIES)}


# =============================================================================
# Datasets
# =============================================================================


def _load_split(path: Path, split: str) -> list[dict[str, Any]]:
    """Return rows whose `split` field matches the requested split.

    `split` is one of train / val / test / regression.
    """
    rows: list[dict[str, Any]] = []
    with jsonlines.open(path) as reader:
        for r in reader:
            if r.get("split") == split:
                rows.append(r)
    return rows


@dataclass
class PreflightItem:
    input_ids: torch.Tensor       # [seq_len]
    attention_mask: torch.Tensor  # [seq_len]
    label: int                    # 0..2
    pair_id: str | None
    raw_query: str
    category: str
    confidence: str
    source: str


class PreflightDataset(Dataset[PreflightItem]):
    """Pre-flight classifier dataset.

    The shared encoder sees the bare query. Truncation at 128 tokens covers
    the long tail of natural queries with comfortable margin.
    """

    def __init__(
        self,
        path: Path,
        tokenizer,
        split: str = "train",
        max_length: int = 128,
    ) -> None:
        self.rows = _load_split(path, split)
        self.tokenizer = tokenizer
        self.max_length = max_length
        # Validate via Pydantic so a malformed row fails loudly.
        for r in self.rows:
            PreflightExample(**r)

    def __len__(self) -> int:
        return len(self.rows)

    def __getitem__(self, idx: int) -> PreflightItem:
        r = self.rows[idx]
        encoded = self.tokenizer(
            r["query"],
            truncation=True,
            max_length=self.max_length,
            padding="max_length",
            return_tensors="pt",
        )
        return PreflightItem(
            input_ids=encoded["input_ids"][0],
            attention_mask=encoded["attention_mask"][0],
            label=PREFLIGHT_LABEL_TO_IDX[r["label"]],
            pair_id=r.get("pair_id"),
            raw_query=r["query"],
            category=r["category"],
            confidence=r["confidence"],
            source=r["source"],
        )


@dataclass
class MemoryItem:
    input_ids: torch.Tensor       # [seq_len]
    attention_mask: torch.Tensor  # [seq_len]
    presence_label: int           # 0 (none) or 1 (≥1 memory)
    category_label: torch.Tensor  # [6] multi-hot
    pair_id: str | None
    raw_user_message: str
    explicit_command: str | None
    source: str


class MemoryDataset(Dataset[MemoryItem]):
    """Memory-extraction classifier dataset.

    Two heads share the encoded representation:
      - presence (binary): does this turn produce ≥1 memory?
      - category (multi-label): which of the 6 MemoryCategory values appear?

    Span text generation is deferred to v1.x per PRD §3.2.4.
    """

    def __init__(
        self,
        path: Path,
        tokenizer,
        split: str = "train",
        max_length: int = 256,
    ) -> None:
        self.rows = _load_split(path, split)
        self.tokenizer = tokenizer
        self.max_length = max_length
        for r in self.rows:
            MemoryExtractionExample(**r)

    def __len__(self) -> int:
        return len(self.rows)

    def __getitem__(self, idx: int) -> MemoryItem:
        r = self.rows[idx]
        # DistilBERT supports two-segment input: A=user_message, B=assistant_response.
        encoded = self.tokenizer(
            r["user_message"],
            r.get("assistant_response", ""),
            truncation="only_first",
            max_length=self.max_length,
            padding="max_length",
            return_tensors="pt",
        )
        memories = r.get("memories_to_extract") or []
        presence = 1 if memories else 0
        category_vec = torch.zeros(len(MEMORY_CATEGORIES), dtype=torch.float32)
        for m in memories:
            cat = m.get("category")
            if cat in MEMORY_CATEGORY_TO_IDX:
                category_vec[MEMORY_CATEGORY_TO_IDX[cat]] = 1.0
        return MemoryItem(
            input_ids=encoded["input_ids"][0],
            attention_mask=encoded["attention_mask"][0],
            presence_label=presence,
            category_label=category_vec,
            pair_id=r.get("pair_id"),
            raw_user_message=r["user_message"],
            explicit_command=r.get("explicit_command"),
            source=r["source"],
        )


# =============================================================================
# Collators
# =============================================================================


def collate_preflight(items: Iterable[PreflightItem]) -> dict[str, Any]:
    items = list(items)
    return {
        "task": "preflight",
        "input_ids": torch.stack([x.input_ids for x in items]),
        "attention_mask": torch.stack([x.attention_mask for x in items]),
        "labels": torch.tensor([x.label for x in items], dtype=torch.long),
        "pair_ids": [x.pair_id for x in items],
        "raw_queries": [x.raw_query for x in items],
        "categories": [x.category for x in items],
        "confidences": [x.confidence for x in items],
        "sources": [x.source for x in items],
    }


def collate_memory(items: Iterable[MemoryItem]) -> dict[str, Any]:
    items = list(items)
    return {
        "task": "memory",
        "input_ids": torch.stack([x.input_ids for x in items]),
        "attention_mask": torch.stack([x.attention_mask for x in items]),
        "presence_labels": torch.tensor(
            [x.presence_label for x in items], dtype=torch.long
        ),
        "category_labels": torch.stack([x.category_label for x in items]),
        "pair_ids": [x.pair_id for x in items],
        "raw_user_messages": [x.raw_user_message for x in items],
        "explicit_commands": [x.explicit_command for x in items],
        "sources": [x.source for x in items],
    }


# =============================================================================
# Tokenizer helper
# =============================================================================


def load_tokenizer(model_name: str = "distilbert-base-uncased"):
    from transformers import AutoTokenizer
    return AutoTokenizer.from_pretrained(model_name)


# =============================================================================
# Multi-task batch interleaver
# =============================================================================


class MultiTaskBatcher:
    """Yields a stream of preflight/memory batches in proportion to dataset sizes.

    The proportion ensures each task sees ~its share of gradient updates per
    epoch. Both DataLoaders are drained; the smaller loader cycles to match.
    """

    def __init__(
        self,
        preflight_loader,
        memory_loader,
        preflight_share: float | None = None,
    ) -> None:
        self.preflight_loader = preflight_loader
        self.memory_loader = memory_loader
        if preflight_share is None:
            # Default to dataset-size proportional sampling.
            n_pre = len(preflight_loader)
            n_mem = len(memory_loader)
            preflight_share = n_pre / max(1, n_pre + n_mem)
        self.preflight_share = preflight_share

    def __iter__(self):
        import random as _r
        # Cycle the smaller loader so the larger one can drain.
        pre_iter = iter(self.preflight_loader)
        mem_iter = iter(self.memory_loader)
        n_pre = len(self.preflight_loader)
        n_mem = len(self.memory_loader)
        total = n_pre + n_mem
        for _ in range(total):
            if _r.random() < self.preflight_share:
                try:
                    yield next(pre_iter)
                except StopIteration:
                    pre_iter = iter(self.preflight_loader)
                    yield next(pre_iter)
            else:
                try:
                    yield next(mem_iter)
                except StopIteration:
                    mem_iter = iter(self.memory_loader)
                    yield next(mem_iter)

    def __len__(self) -> int:
        return len(self.preflight_loader) + len(self.memory_loader)
