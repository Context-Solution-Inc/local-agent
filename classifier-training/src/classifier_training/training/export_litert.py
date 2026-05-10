"""ai-edge-torch → .tflite export — `ct-export-litert`.

Converts a trained PyTorch checkpoint into a single LiteRT (.tflite) artifact
with all three classifier heads exposed as named outputs:
  - preflight_logits      [B, 3]
  - presence_logits       [B, 2]
  - category_logits       [B, 6]

The Android side (M4 / WS-8) loads this .tflite via Play Services LiteRT
and reads whichever head it needs for the active task.

We export from FP32 — ai-edge-torch's own quantization API (ai-edge-quantizer)
is the canonical path for LiteRT INT8 weights. Pre-quantizing in PyTorch
adds tracing complexity and isn't required for size: ai-edge-quantizer can
produce an INT8 .tflite from the FP32 PyTorch model.

Usage:
  ct-export-litert \\
      --ckpt eval/runs/phaseF_full_<ts>/best.pt \\
      --output models/preflight_memory_shared_v1.0.0.tflite

Optional INT8 weight quantization (recommended for shipping):
  ct-export-litert ... --int8
"""

from __future__ import annotations

from pathlib import Path

import click


@click.command()
@click.option(
    "--ckpt",
    type=click.Path(exists=True, dir_okay=False, path_type=Path),
    required=True,
    help="FP32 PyTorch checkpoint (best.pt from ct-train-classifier).",
)
@click.option(
    "--output",
    type=click.Path(dir_okay=False, path_type=Path),
    required=True,
    help="Output .tflite path. Convention: models/preflight_memory_shared_v1.0.0.tflite",
)
@click.option("--base-model", default="distilbert-base-uncased")
@click.option("--max-length", default=128, type=int, help="Static sequence length for the exported graph.")
@click.option(
    "--int8",
    "int8_weights",
    is_flag=True,
    default=False,
    help="Apply INT8 weight quantization via ai-edge-quantizer post-conversion.",
)
def main(
    ckpt: Path,
    output: Path,
    base_model: str,
    max_length: int,
    int8_weights: bool,
) -> None:
    """Export a checkpoint to a single LiteRT .tflite with all heads."""
    import torch
    from rich.console import Console

    from .model import ModelConfig, SharedEncoderTwoHeads

    console = Console()
    output.parent.mkdir(parents=True, exist_ok=True)

    console.print(f"[bold]Loading FP32 checkpoint[/bold] {ckpt}")
    model = SharedEncoderTwoHeads(ModelConfig(base_model_name=base_model))
    model.load_state_dict(torch.load(ckpt, map_location="cpu", weights_only=True))
    model.eval()

    exportable = model.export_wrapper().eval()

    # ai-edge-torch needs concrete sample inputs to trace the graph.
    sample_input_ids = torch.zeros((1, max_length), dtype=torch.long)
    sample_attn = torch.ones((1, max_length), dtype=torch.long)

    console.print(
        f"[bold]Tracing + converting to LiteRT[/bold] "
        f"(seq_len={max_length}, int8_weights={int8_weights})..."
    )
    # Note: ai-edge-torch was renamed to litert-torch in 2025; both are
    # installed but the actively-maintained API is in litert_torch.
    try:
        import litert_torch as edge_runtime
    except ImportError:
        try:
            import ai_edge_torch as edge_runtime  # type: ignore
        except ImportError as e:
            raise click.ClickException(
                "litert-torch (or ai-edge-torch) required. "
                "Install via: pip install -e '.[training]'"
            ) from e

    # First convert to FP32 .tflite via litert-torch.
    edge_model = edge_runtime.convert(
        exportable,
        (sample_input_ids, sample_attn),
    )
    edge_model.export(str(output))
    size_fp32_mb = output.stat().st_size / 1e6
    console.print(
        f"[green]Wrote FP32 .tflite[/green] → {output} ({size_fp32_mb:.1f} MB)"
    )

    if int8_weights:
        # Post-quantize the FP32 .tflite to INT8 weights via ai-edge-quantizer.
        # Encoder-style classifier needs weight-only INT8 (not generative
        # dynamic-quant recipe, which is for LLM-style models).
        console.print("[bold]Applying INT8 weight-only quantization via ai-edge-quantizer...[/bold]")
        from ai_edge_quantizer import Quantizer
        from ai_edge_quantizer import qtyping
        from ai_edge_quantizer.recipe_manager import AlgorithmName

        q = Quantizer(float_model=str(output))
        q.update_quantization_recipe(
            regex=".*",
            operation_name=qtyping.TFLOperationName.ALL_SUPPORTED,
            algorithm_key=AlgorithmName.MIN_MAX_UNIFORM_QUANT,
            op_config=qtyping.OpQuantizationConfig(
                weight_tensor_config=qtyping.TensorQuantizationConfig(
                    num_bits=8,
                    symmetric=True,
                    granularity=qtyping.QuantGranularity.CHANNELWISE,
                ),
                compute_precision=qtyping.ComputePrecision.INTEGER,
                explicit_dequantize=False,
            ),
        )
        result = q.quantize()
        result.export_model(str(output), overwrite=True)
        size_int8_mb = output.stat().st_size / 1e6
        console.print(
            f"[green]Wrote INT8 .tflite[/green] → {output} "
            f"({size_int8_mb:.1f} MB, {(1 - size_int8_mb/size_fp32_mb)*100:.0f}% reduction from FP32)"
        )

    # Sanity test — run a forward pass on the .tflite via ai-edge-litert.
    console.print("[bold]Sanity test: running .tflite via LiteRT runtime...[/bold]")
    try:
        from ai_edge_litert.interpreter import Interpreter
    except ImportError:
        try:
            from tflite_runtime.interpreter import Interpreter  # type: ignore
        except ImportError:
            console.print(
                "[yellow]Skipping sanity test — no LiteRT/tflite runtime available.[/yellow]"
            )
            return

    interpreter = Interpreter(model_path=str(output))
    interpreter.allocate_tensors()
    input_details = interpreter.get_input_details()
    output_details = interpreter.get_output_details()
    console.print("[dim]Inputs:[/dim]")
    for d in input_details:
        console.print(f"  {d['name']}  shape={list(d['shape'])}  dtype={d['dtype']}")
    console.print("[dim]Outputs:[/dim]")
    for d in output_details:
        console.print(f"  {d['name']}  shape={list(d['shape'])}  dtype={d['dtype']}")

    # Set sample input + invoke once to confirm the graph runs end-to-end.
    import numpy as np
    interpreter.set_tensor(input_details[0]["index"], sample_input_ids.numpy())
    interpreter.set_tensor(input_details[1]["index"], sample_attn.numpy())
    interpreter.invoke()
    for d in output_details:
        out = interpreter.get_tensor(d["index"])
        console.print(f"  [green]{d['name']}[/green] sample shape={list(out.shape)} max={float(np.abs(out).max()):.3f}")
    console.print("[bold green]Sanity test passed.[/bold green]")


if __name__ == "__main__":
    main()
