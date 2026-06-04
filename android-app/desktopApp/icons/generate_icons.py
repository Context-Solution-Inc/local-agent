#!/usr/bin/env python3
"""Generate the Mobile Agent app icons from the Android brand mark.

The brand mark is the original Android launcher icon: a white speech-bubble glyph
on a dark-navy plate (`#1F2937`). The glyph is committed next to this script as

  ic_launcher_foreground.png  — white bubble, transparent margins (1024x1024)

rasterized from the Android vector drawable
(androidApp/src/main/res/drawable/ic_launcher_foreground.xml) so the desktop,
tray, window, installer, in-app chat logo, and (staged) iOS icon all match the
launcher icon. To regenerate the glyph after editing that vector:

    inkscape bubble.svg --export-type=png --export-filename=ic_launcher_foreground.png -w 1024 -h 1024

(where bubble.svg wraps the vector's <path> — the pathData is already SVG path
syntax). This script then composites it onto the navy plate and emits:

  desktopApp/icons/icon.png    512x512  — Linux (.deb/.rpm) installer
  desktopApp/icons/icon.ico             — Windows (.msi/.exe), multi-resolution
  desktopApp/icons/icon.icns            — macOS (.dmg/.pkg)
  desktopApp/src/main/resources/icon.png 512x512 — in-app tray/window resource
  iosApp/Assets.xcassets/AppIcon.appiconset/icon-1024.png 1024 — staged iOS icon

Desktop/in-app variants are rounded squares; the iOS variant is an opaque,
un-rounded 1024 square (iOS applies its own mask and forbids alpha).

Reproducible (no randomness). Re-run after changing the artwork:

    python3 desktopApp/icons/generate_icons.py

Requires Pillow (PIL). The generated icons are committed so the build needs no
image toolchain; this script is the source of truth for regenerating them.
"""
from __future__ import annotations

import shutil
from pathlib import Path

from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
ANDROID_APP = HERE.parent.parent  # .../android-app
RES_DIR = HERE.parent / "src" / "main" / "resources"
IOS_ICONSET = ANDROID_APP / "iosApp" / "Assets.xcassets" / "AppIcon.appiconset"

BRAND_NAVY = (0x1F, 0x29, 0x37, 0xFF)  # @color/ic_launcher_background
MASTER = 1024
CORNER_RADIUS = 224  # rounded-square corner radius at the master scale


def _composite() -> Image.Image:
    """White speech-bubble glyph over the dark-navy plate (opaque RGBA master)."""
    base = Image.new("RGBA", (MASTER, MASTER), BRAND_NAVY)
    fg = Image.open(HERE / "ic_launcher_foreground.png").convert("RGBA")
    if fg.size != (MASTER, MASTER):
        fg = fg.resize((MASTER, MASTER), Image.LANCZOS)
    base.alpha_composite(fg)
    return base


def _rounded(img: Image.Image) -> Image.Image:
    """Apply a rounded-square alpha mask (desktop + in-app look)."""
    mask = Image.new("L", (MASTER, MASTER), 0)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, MASTER - 1, MASTER - 1), radius=CORNER_RADIUS, fill=255
    )
    out = img.copy()
    out.putalpha(mask)
    return out


def main() -> None:
    composite = _composite()
    rounded = _rounded(composite)

    # --- Desktop: rounded-square master ---
    RES_DIR.mkdir(parents=True, exist_ok=True)

    png = rounded.resize((512, 512), Image.LANCZOS)
    png_path = HERE / "icon.png"
    png.save(png_path, format="PNG")
    shutil.copyfile(png_path, RES_DIR / "icon.png")

    rounded.save(
        HERE / "icon.ico",
        format="ICO",
        sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)],
    )
    rounded.save(HERE / "icon.icns", format="ICNS")

    # --- iOS app icon (staged): opaque 1024 square, no alpha, no rounding ---
    IOS_ICONSET.mkdir(parents=True, exist_ok=True)
    ios = Image.new("RGB", (MASTER, MASTER), BRAND_NAVY[:3])
    ios.paste(composite, (0, 0), composite)
    ios_path = IOS_ICONSET / "icon-1024.png"
    ios.save(ios_path, format="PNG")

    for p in (
        png_path,
        HERE / "icon.ico",
        HERE / "icon.icns",
        RES_DIR / "icon.png",
        ios_path,
    ):
        print(f"wrote {p}")


if __name__ == "__main__":
    main()
