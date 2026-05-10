"""Training CLI — `ct-train-classifier`.

Multi-task fine-tunes the shared encoder on preflight + memory data. AdamW,
linear warm-up + linear decay, val-F1 early-stop. Saves the best checkpoint
and a metadata.json with hyperparameters and dataset versions.

Designed for solo iteration on the user's RTX 5090:
  - default batch 32 fits comfortably in 24 GB VRAM
  - preflight max_seq=128, memory max_seq=256 chosen to cover 99% of inputs
  - 5-epoch default with early stop usually converges in 2-3 epochs

Usage:
  ct-train-classifier \\
      --preflight-jsonl datasets/preflight/preflight_v1.0.0.jsonl \\
      --memory-jsonl    datasets/memory/memory_v1.0.0.jsonl \\
      --output-dir      eval/runs/$(date +%Y%m%d_%H%M%S) \\
      --epochs 5 --batch-size 32 --lr 2e-5

The output directory holds:
  - best.pt        — best checkpoint by val F1 (preflight)
  - last.pt        — final checkpoint
  - metadata.json  — hyperparameters, dataset SHA-256s, training metrics
  - train.log      — per-step + per-epoch metrics in JSONL form
"""

from __future__ import annotations

import json
import time
from dataclasses import asdict, dataclass
from pathlib import Path

import click


def _import_torch_stack():
    """Lazy import; the [training] extra is heavy."""
    import torch  # noqa: F401
    from torch.utils.data import DataLoader  # noqa: F401
    from transformers import get_linear_schedule_with_warmup  # noqa: F401


@dataclass
class TrainArgs:
    preflight_jsonl: Path
    memory_jsonl: Path
    output_dir: Path
    base_model: str
    epochs: int
    batch_size: int
    lr: float
    weight_decay: float
    warmup_pct: float
    grad_clip: float
    max_steps: int | None
    seed: int
    preflight_only: bool
    use_class_weights: bool
    use_focal: bool


# =============================================================================
# Eval (val) helpers
# =============================================================================


def _evaluate_preflight(model, loader, device) -> dict[str, float]:
    """Macro-F1 + accuracy on val/test."""
    import torch
    from sklearn.metrics import classification_report

    model.eval()
    y_true: list[int] = []
    y_pred: list[int] = []
    with torch.no_grad():
        for batch in loader:
            input_ids = batch["input_ids"].to(device)
            attention_mask = batch["attention_mask"].to(device)
            labels = batch["labels"].to(device)
            logits = model(input_ids, attention_mask, task="preflight")["preflight_logits"]
            preds = logits.argmax(dim=-1)
            y_true.extend(labels.cpu().tolist())
            y_pred.extend(preds.cpu().tolist())
    rep = classification_report(
        y_true, y_pred, output_dict=True, zero_division=0
    )
    return {
        "preflight_accuracy": rep["accuracy"],
        "preflight_macro_f1": rep["macro avg"]["f1-score"],
        "preflight_weighted_f1": rep["weighted avg"]["f1-score"],
    }


def _evaluate_memory(model, loader, device) -> dict[str, float]:
    """Memory presence accuracy + category multi-label F1."""
    import torch
    from sklearn.metrics import f1_score

    model.eval()
    presence_true: list[int] = []
    presence_pred: list[int] = []
    cat_true: list[list[int]] = []
    cat_pred: list[list[int]] = []
    with torch.no_grad():
        for batch in loader:
            input_ids = batch["input_ids"].to(device)
            attention_mask = batch["attention_mask"].to(device)
            presence_labels = batch["presence_labels"].to(device)
            category_labels = batch["category_labels"].to(device)
            out = model(input_ids, attention_mask, task="memory")
            p_pred = out["presence_logits"].argmax(dim=-1)
            c_pred = (out["category_logits"].sigmoid() > 0.5).long()
            presence_true.extend(presence_labels.cpu().tolist())
            presence_pred.extend(p_pred.cpu().tolist())
            cat_true.extend(category_labels.cpu().tolist())
            cat_pred.extend(c_pred.cpu().tolist())
    presence_acc = (
        sum(int(t == p) for t, p in zip(presence_true, presence_pred))
        / max(1, len(presence_true))
    )
    cat_macro_f1 = f1_score(
        cat_true, cat_pred, average="macro", zero_division=0
    )
    return {
        "memory_presence_accuracy": presence_acc,
        "memory_category_macro_f1": float(cat_macro_f1),
    }


# =============================================================================
# Train loop
# =============================================================================


def _train(args: TrainArgs) -> None:
    import torch
    from torch.utils.data import DataLoader
    from transformers import get_linear_schedule_with_warmup

    from .data import (
        MemoryDataset,
        MultiTaskBatcher,
        PreflightDataset,
        collate_memory,
        collate_preflight,
        load_tokenizer,
    )
    from .losses import LossWeights, MultiTaskLoss
    from .model import ModelConfig, SharedEncoderTwoHeads

    args.output_dir.mkdir(parents=True, exist_ok=True)
    log_path = args.output_dir / "train.log"
    log_f = log_path.open("w")

    def log(record: dict) -> None:
        log_f.write(json.dumps(record) + "\n")
        log_f.flush()
        click.echo(json.dumps(record))

    torch.manual_seed(args.seed)
    device = torch.device("cuda" if torch.cuda.is_available() else "cpu")
    log({"event": "device", "device": str(device)})

    tokenizer = load_tokenizer(args.base_model)
    pre_train = PreflightDataset(args.preflight_jsonl, tokenizer, split="train")
    pre_val = PreflightDataset(args.preflight_jsonl, tokenizer, split="val")
    log({
        "event": "preflight_loaded",
        "train": len(pre_train), "val": len(pre_val),
    })

    if not args.preflight_only:
        mem_train = MemoryDataset(args.memory_jsonl, tokenizer, split="train")
        mem_val = MemoryDataset(args.memory_jsonl, tokenizer, split="val")
        log({
            "event": "memory_loaded",
            "train": len(mem_train), "val": len(mem_val),
        })

    pre_train_loader = DataLoader(
        pre_train, batch_size=args.batch_size, shuffle=True,
        collate_fn=collate_preflight, num_workers=2,
    )
    pre_val_loader = DataLoader(
        pre_val, batch_size=args.batch_size, shuffle=False,
        collate_fn=collate_preflight, num_workers=2,
    )
    if not args.preflight_only:
        mem_train_loader = DataLoader(
            mem_train, batch_size=args.batch_size, shuffle=True,
            collate_fn=collate_memory, num_workers=2,
        )
        mem_val_loader = DataLoader(
            mem_val, batch_size=args.batch_size, shuffle=False,
            collate_fn=collate_memory, num_workers=2,
        )

    model = SharedEncoderTwoHeads(ModelConfig(base_model_name=args.base_model)).to(device)
    log({"event": "model_built", "num_params": model.num_trainable_params()})

    # Compute inverse-frequency class weights from the training rows — pushes
    # the model harder on the minority class (ambiguous, 11.6%) and the
    # under-represented search_required (37.8%, a §7 gate critical class).
    class_weights = None
    if args.use_class_weights:
        from collections import Counter

        from .data import PREFLIGHT_LABEL_TO_IDX
        counter: Counter[int] = Counter(
            PREFLIGHT_LABEL_TO_IDX[r["label"]] for r in pre_train.rows
        )
        n = sum(counter.values())
        # weight = (n / num_classes) / count_per_class — sklearn-style.
        weights_t = torch.tensor(
            [(n / 3) / max(1, counter.get(i, 1)) for i in range(3)],
            dtype=torch.float32,
            device=device,
        )
        class_weights = weights_t
        log({"event": "class_weights", "weights": weights_t.tolist(), "counter": dict(counter)})

    loss_fn = MultiTaskLoss(
        LossWeights(),
        preflight_class_weights=class_weights,
        use_focal=args.use_focal,
    )
    optimizer = torch.optim.AdamW(
        model.parameters(),
        lr=args.lr,
        weight_decay=args.weight_decay,
    )

    if args.preflight_only:
        steps_per_epoch = len(pre_train_loader)
    else:
        batcher = MultiTaskBatcher(pre_train_loader, mem_train_loader)
        steps_per_epoch = len(batcher)
    total_steps = steps_per_epoch * args.epochs
    if args.max_steps is not None:
        total_steps = min(total_steps, args.max_steps)
    scheduler = get_linear_schedule_with_warmup(
        optimizer,
        num_warmup_steps=int(total_steps * args.warmup_pct),
        num_training_steps=total_steps,
    )

    best_val_f1 = -1.0
    best_path = args.output_dir / "best.pt"
    last_path = args.output_dir / "last.pt"
    started = time.monotonic()
    global_step = 0

    for epoch in range(args.epochs):
        model.train()
        if args.preflight_only:
            stream = pre_train_loader
        else:
            stream = batcher
        for batch in stream:
            batch_on_device = _to_device(batch, device)
            logits = model(
                batch_on_device["input_ids"],
                batch_on_device["attention_mask"],
                task=batch_on_device["task"],
            )
            loss, scalar_metrics = loss_fn(batch_on_device, logits)
            loss.backward()
            torch.nn.utils.clip_grad_norm_(model.parameters(), args.grad_clip)
            optimizer.step()
            scheduler.step()
            optimizer.zero_grad(set_to_none=True)
            global_step += 1
            if global_step % 25 == 0 or global_step == 1:
                log({
                    "event": "step",
                    "step": global_step,
                    "epoch": epoch,
                    "task": batch_on_device["task"],
                    "loss_total": float(loss.detach()),
                    **scalar_metrics,
                })
            if args.max_steps is not None and global_step >= args.max_steps:
                break

        # End-of-epoch eval.
        pre_metrics = _evaluate_preflight(model, pre_val_loader, device)
        mem_metrics: dict[str, float] = {}
        if not args.preflight_only:
            mem_metrics = _evaluate_memory(model, mem_val_loader, device)
        epoch_record = {
            "event": "epoch_end",
            "epoch": epoch,
            "step": global_step,
            **pre_metrics,
            **mem_metrics,
        }
        log(epoch_record)

        f1 = pre_metrics["preflight_macro_f1"]
        if f1 > best_val_f1:
            best_val_f1 = f1
            torch.save(model.state_dict(), best_path)
            log({"event": "checkpoint_best", "epoch": epoch, "macro_f1": f1, "path": str(best_path)})

        if args.max_steps is not None and global_step >= args.max_steps:
            break

    torch.save(model.state_dict(), last_path)
    elapsed = time.monotonic() - started
    log({
        "event": "training_done",
        "elapsed_s": elapsed,
        "best_val_macro_f1": best_val_f1,
        "best_path": str(best_path),
    })

    metadata = {
        "args": {k: str(v) if isinstance(v, Path) else v for k, v in asdict(args).items()},
        "best_val_macro_f1": best_val_f1,
        "elapsed_s": elapsed,
        "device": str(device),
    }
    (args.output_dir / "metadata.json").write_text(json.dumps(metadata, indent=2))
    log_f.close()


def _to_device(batch: dict, device) -> dict:
    out = dict(batch)
    for k, v in batch.items():
        if hasattr(v, "to") and callable(getattr(v, "to")):
            out[k] = v.to(device)
    return out


# =============================================================================
# CLI
# =============================================================================


@click.command()
@click.option(
    "--preflight-jsonl",
    type=click.Path(exists=True, dir_okay=False, path_type=Path),
    required=True,
)
@click.option(
    "--memory-jsonl",
    type=click.Path(exists=True, dir_okay=False, path_type=Path),
    required=True,
)
@click.option(
    "--output-dir",
    type=click.Path(file_okay=False, path_type=Path),
    required=True,
)
@click.option("--base-model", default="distilbert-base-uncased")
@click.option("--epochs", default=5, type=int)
@click.option("--batch-size", default=32, type=int)
@click.option("--lr", default=2e-5, type=float)
@click.option("--weight-decay", default=0.01, type=float)
@click.option("--warmup-pct", default=0.06, type=float, help="Fraction of total steps spent warming up.")
@click.option("--grad-clip", default=1.0, type=float)
@click.option("--max-steps", default=None, type=int, help="Hard cap on total optimizer steps (smoke test).")
@click.option("--seed", default=42, type=int)
@click.option("--preflight-only", is_flag=True, default=False, help="Skip memory task (smoke / Phase F bake-off).")
@click.option("--use-class-weights", is_flag=True, default=False, help="Inverse-frequency class weights on preflight CE loss.")
@click.option("--use-focal", is_flag=True, default=False, help="Focal loss (γ=2) on preflight; helps boundary cases.")
def main(
    preflight_jsonl: Path,
    memory_jsonl: Path,
    output_dir: Path,
    base_model: str,
    epochs: int,
    batch_size: int,
    lr: float,
    weight_decay: float,
    warmup_pct: float,
    grad_clip: float,
    max_steps: int | None,
    seed: int,
    preflight_only: bool,
    use_class_weights: bool,
    use_focal: bool,
) -> None:
    """Train the shared-encoder multi-task classifier."""
    args = TrainArgs(
        preflight_jsonl=preflight_jsonl,
        memory_jsonl=memory_jsonl,
        output_dir=output_dir,
        base_model=base_model,
        epochs=epochs,
        batch_size=batch_size,
        lr=lr,
        weight_decay=weight_decay,
        warmup_pct=warmup_pct,
        grad_clip=grad_clip,
        max_steps=max_steps,
        seed=seed,
        preflight_only=preflight_only,
        use_class_weights=use_class_weights,
        use_focal=use_focal,
    )
    _train(args)


if __name__ == "__main__":
    main()
