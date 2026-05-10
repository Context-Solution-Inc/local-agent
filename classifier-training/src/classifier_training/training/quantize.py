"""INT8 dynamic quantization — `ct-quantize`.

Loads a trained FP32 checkpoint, applies torch.ao.quantization.quantize_dynamic
on the Linear layers (encoder + heads), and saves the quantized state_dict.
Re-running `ct-eval-classifier` on the quantized checkpoint confirms the
accuracy drop is within budget (M3_PLAN.md Phase G: ≤1pt on any §7 metric).

Why dynamic and not QAT?
  - Dynamic INT8 quantization is post-hoc and works on any trained model.
  - It typically loses <1pt accuracy on encoder-only fine-tunes.
  - QAT (one extra epoch with fake-quant nodes) is the documented escalation
    if dynamic loses >1pt; we don't pre-emptively pay for it.

Usage:
  ct-quantize \\
      --ckpt eval/runs/phaseF_full_<ts>/best.pt \\
      --output eval/runs/phaseF_quantized/best_int8.pt
"""

from __future__ import annotations

from pathlib import Path

import click


@click.command()
@click.option(
    "--ckpt",
    type=click.Path(exists=True, dir_okay=False, path_type=Path),
    required=True,
    help="FP32 checkpoint to quantize.",
)
@click.option(
    "--output",
    type=click.Path(dir_okay=False, path_type=Path),
    required=True,
    help="Where to write the INT8 state_dict.",
)
@click.option("--base-model", default="distilbert-base-uncased")
def main(ckpt: Path, output: Path, base_model: str) -> None:
    """Apply INT8 dynamic quantization to a trained checkpoint."""
    import torch
    from torch import nn
    from rich.console import Console

    from .model import ModelConfig, SharedEncoderTwoHeads

    console = Console()
    output.parent.mkdir(parents=True, exist_ok=True)

    console.print(f"[bold]Loading FP32 checkpoint[/bold] {ckpt}")
    model = SharedEncoderTwoHeads(ModelConfig(base_model_name=base_model))
    model.load_state_dict(torch.load(ckpt, map_location="cpu", weights_only=True))
    model.eval()

    fp32_size = ckpt.stat().st_size
    fp32_params = sum(p.numel() for p in model.parameters())
    console.print(
        f"[dim]FP32: {fp32_params / 1e6:.1f}M params, on-disk {fp32_size / 1e6:.1f} MB[/dim]"
    )

    console.print("[bold]Applying INT8 dynamic quantization on Linear layers...[/bold]")
    quantized = torch.ao.quantization.quantize_dynamic(
        model,
        {nn.Linear},
        dtype=torch.qint8,
    )

    # Save the full quantized model (state_dict alone doesn't preserve quant
    # state). Use torch.save on the module for full reconstructability.
    torch.save(quantized, output)
    int8_size = output.stat().st_size
    console.print(
        f"[green]Wrote INT8 model[/green] → {output} "
        f"(on-disk {int8_size / 1e6:.1f} MB, "
        f"{(1 - int8_size / fp32_size) * 100:.0f}% reduction)"
    )

    console.print(
        "\n[dim]Next: re-run ct-eval-classifier with --ckpt-quantized "
        f"{output} to confirm accuracy drop ≤ 1pt on every §7 metric.[/dim]"
    )


if __name__ == "__main__":
    main()
