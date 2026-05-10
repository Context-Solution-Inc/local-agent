"""Shared-encoder, multi-head classifier for both M3 tasks.

Single DistilBERT backbone. Three heads:
  - preflight_head        — 3-class softmax over (search_required, not_required, ambiguous)
  - memory_presence_head  — 2-class softmax over (no extraction, ≥1 extraction)
  - memory_category_head  — 6-way multi-label sigmoid over MemoryCategory enum

The forward routes the encoded [CLS] vector through the head corresponding to
the batch's `task`. M3 Phase F will decide whether multi-task training beats
two separate fine-tunes; the shared encoder is the §4.2 memory-budget choice
(saves ~50 MB on-device).
"""

from __future__ import annotations

from dataclasses import dataclass

import torch
from torch import nn


@dataclass
class ModelConfig:
    base_model_name: str = "distilbert-base-uncased"
    hidden_dim: int = 768
    preflight_classes: int = 3
    memory_presence_classes: int = 2
    memory_categories: int = 6
    head_dropout: float = 0.1


class SharedEncoderTwoHeads(nn.Module):
    """The full classifier: DistilBERT encoder + 3 task heads."""

    def __init__(self, config: ModelConfig | None = None) -> None:
        super().__init__()
        cfg = config or ModelConfig()
        self.config = cfg

        # Lazy import so the [training] extra is only required when training.
        from transformers import DistilBertModel

        self.encoder = DistilBertModel.from_pretrained(cfg.base_model_name)

        self.dropout = nn.Dropout(cfg.head_dropout)
        self.preflight_head = nn.Linear(cfg.hidden_dim, cfg.preflight_classes)
        self.memory_presence_head = nn.Linear(cfg.hidden_dim, cfg.memory_presence_classes)
        self.memory_category_head = nn.Linear(cfg.hidden_dim, cfg.memory_categories)

    def encode(
        self,
        input_ids: torch.Tensor,
        attention_mask: torch.Tensor,
    ) -> torch.Tensor:
        """Encode and pool. Returns the [CLS] hidden state, shape [B, H]."""
        out = self.encoder(input_ids=input_ids, attention_mask=attention_mask)
        # DistilBertModel returns last_hidden_state [B, T, H]; pool by [CLS] (index 0).
        cls_hidden = out.last_hidden_state[:, 0, :]
        return self.dropout(cls_hidden)

    def forward(
        self,
        input_ids: torch.Tensor,
        attention_mask: torch.Tensor,
        task: str,
    ) -> dict[str, torch.Tensor]:
        """Return logits for the requested task.

        For task='preflight' returns {'preflight_logits': [B, 3]}.
        For task='memory'    returns {'presence_logits': [B, 2],
                                      'category_logits': [B, 6]}.
        Both heads share the same encoded representation; we don't re-encode.
        """
        pooled = self.encode(input_ids, attention_mask)
        if task == "preflight":
            return {"preflight_logits": self.preflight_head(pooled)}
        if task == "memory":
            return {
                "presence_logits": self.memory_presence_head(pooled),
                "category_logits": self.memory_category_head(pooled),
            }
        raise ValueError(f"Unknown task {task!r}")

    def num_trainable_params(self) -> int:
        return sum(p.numel() for p in self.parameters() if p.requires_grad)

    def export_wrapper(self) -> "ExportableSharedEncoder":
        """Return a wrapper with a fixed forward(input_ids, attention_mask)
        signature emitting all three head outputs as a tuple — ai-edge-torch
        and ONNX both need static signatures (no `task` string arg).
        """
        return ExportableSharedEncoder(self)


class ExportableSharedEncoder(nn.Module):
    """Static-signature wrapper for export — emits all task heads at once.

    The agent ignores the heads it doesn't need on a given query, paying a
    tiny compute cost (~1.4M extra params) for a much simpler deployment
    surface (one .tflite, three named outputs).
    """

    def __init__(self, base: SharedEncoderTwoHeads) -> None:
        super().__init__()
        self.encoder = base.encoder
        self.dropout = base.dropout
        self.preflight_head = base.preflight_head
        self.memory_presence_head = base.memory_presence_head
        self.memory_category_head = base.memory_category_head

    def forward(
        self,
        input_ids: torch.Tensor,
        attention_mask: torch.Tensor,
    ) -> tuple[torch.Tensor, torch.Tensor, torch.Tensor]:
        """Returns (preflight_logits, presence_logits, category_logits)."""
        out = self.encoder(input_ids=input_ids, attention_mask=attention_mask)
        cls_hidden = self.dropout(out.last_hidden_state[:, 0, :])
        return (
            self.preflight_head(cls_hidden),
            self.memory_presence_head(cls_hidden),
            self.memory_category_head(cls_hidden),
        )
