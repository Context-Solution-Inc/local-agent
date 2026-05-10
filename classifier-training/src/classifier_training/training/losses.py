"""Multi-task loss for the shared-encoder classifier.

Total loss = α · preflight_loss + β · memory_presence_CE + γ · memory_category_BCE

Where `preflight_loss` is either CrossEntropy (default) or Focal loss
(`use_focal=True`). Focal loss down-weights easy examples and focuses
gradient on the boundary cases — useful when standard CE plateaus near
a precision/recall ceiling driven by ambiguous-class noise.

Class weights: optional per-class weighting for the preflight CE loss.
"""

from __future__ import annotations

from dataclasses import dataclass

import torch
import torch.nn.functional as F
from torch import nn


class FocalLoss(nn.Module):
    """Focal loss for multi-class classification (Lin et al., 2017).

    L = -α_t (1 - p_t)^γ log(p_t)

    γ=2 is the standard setting and what we use here.
    """

    def __init__(self, gamma: float = 2.0, weight: torch.Tensor | None = None) -> None:
        super().__init__()
        self.gamma = gamma
        self.weight = weight

    def forward(self, logits: torch.Tensor, target: torch.Tensor) -> torch.Tensor:
        log_probs = F.log_softmax(logits, dim=-1)
        probs = log_probs.exp()
        target_log_probs = log_probs.gather(1, target.unsqueeze(1)).squeeze(1)
        target_probs = probs.gather(1, target.unsqueeze(1)).squeeze(1)
        focal_factor = (1 - target_probs) ** self.gamma
        loss = -focal_factor * target_log_probs
        if self.weight is not None:
            class_weights_per_sample = self.weight[target]
            loss = loss * class_weights_per_sample
        return loss.mean()


@dataclass
class LossWeights:
    preflight: float = 1.0
    memory_presence: float = 1.0
    memory_category: float = 1.0


class MultiTaskLoss:
    """Per-batch loss computer. Stateless — instantiate once and reuse."""

    def __init__(
        self,
        weights: LossWeights | None = None,
        preflight_class_weights: torch.Tensor | None = None,
        use_focal: bool = False,
        focal_gamma: float = 2.0,
    ) -> None:
        self.weights = weights or LossWeights()
        # Separate loss objects per task — preflight has 3 classes (with
        # optional CE class weights or focal loss), memory presence has 2.
        if use_focal:
            self._preflight_loss: nn.Module = FocalLoss(
                gamma=focal_gamma, weight=preflight_class_weights
            )
        else:
            self._preflight_loss = nn.CrossEntropyLoss(weight=preflight_class_weights)
        self._presence_ce = nn.CrossEntropyLoss()
        self._bce = nn.BCEWithLogitsLoss()

    def __call__(
        self,
        batch: dict,
        logits: dict[str, torch.Tensor],
    ) -> tuple[torch.Tensor, dict[str, float]]:
        """Returns (total_loss, scalar_metrics_for_logging)."""
        task = batch["task"]
        if task == "preflight":
            loss_pre = self._preflight_loss(logits["preflight_logits"], batch["labels"])
            total = self.weights.preflight * loss_pre
            return total, {"loss/preflight": float(loss_pre.detach())}
        if task == "memory":
            loss_pre = self._presence_ce(logits["presence_logits"], batch["presence_labels"])
            loss_cat = self._bce(logits["category_logits"], batch["category_labels"])
            total = (
                self.weights.memory_presence * loss_pre
                + self.weights.memory_category * loss_cat
            )
            return total, {
                "loss/memory_presence": float(loss_pre.detach()),
                "loss/memory_category": float(loss_cat.detach()),
            }
        raise ValueError(f"Unknown task {task!r}")
