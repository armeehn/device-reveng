#!/usr/bin/env python3
"""Render the Riposte boot animation. No frames in git: brand palette (RL-BRAND-001,
night variant) and JetBrains Mono from the launcher, drawn with Pillow.

    ┌──────────────────────────────────────────────┐ 1920x720, ink field #14110E
    │                                              │
    │              R I P O S T E                   │ dimmed bone #CFC7B8, ExtraBold
    │              ▮▮▮▮▮▮▮▮▮▮▮▮▮                   │ marigold #C9891F bar, hard edge,
    │                                              │ fills left→right, loops until boot ends
    └──────────────────────────────────────────────┘

Usage: make.py OUT.zip    (stored, not deflated: Android's bootanimation requires it)
Runs where Pillow lives (LXC 111); build.sh takes the finished zip from --apps.
"""
import io
import os
import sys
import zipfile

from PIL import Image, ImageDraw, ImageFont

W, H, FPS, SECONDS = 1920, 720, 30, 2
INK_FIELD, BONE_DIM, MARIGOLD = (0x14, 0x11, 0x0E), (0xCF, 0xC7, 0xB8), (0xC9, 0x89, 0x1F)
TEXT_SIZE, TEXT_BOTTOM = 112, 390      # wordmark baseline area
BAR_W, BAR_H, BAR_Y = 520, 14, 430
FONT = os.path.join(os.path.dirname(__file__), "..", "..", "launcher", "app", "src", "main",
                    "res", "font", "jetbrains_mono_extrabold.ttf")


def frame(i, font, frames):
    img = Image.new("RGB", (W, H), INK_FIELD)
    d = ImageDraw.Draw(img)
    x0, y0, x1, y1 = d.textbbox((0, 0), "RIPOSTE", font=font)
    d.text(((W - (x1 - x0)) / 2 - x0, TEXT_BOTTOM - y1), "RIPOSTE", font=font, fill=BONE_DIM)

    # The bar fills over one loop; frame 0 is empty so the loop seam reads as a restart.
    fill = BAR_W * i // (frames - 1)
    left = (W - BAR_W) // 2
    if fill:
        d.rectangle((left, BAR_Y, left + fill - 1, BAR_Y + BAR_H - 1), fill=MARIGOLD)
    return img


def main(out):
    font = ImageFont.truetype(FONT, TEXT_SIZE)
    frames = FPS * SECONDS
    with zipfile.ZipFile(out, "w", zipfile.ZIP_STORED) as z:
        z.writestr("desc.txt", f"{W} {H} {FPS}\np 0 0 part0\n")
        for i in range(frames):
            buf = io.BytesIO()
            frame(i, font, frames).save(buf, "PNG", optimize=True)
            z.writestr(f"part0/{i:04d}.png", buf.getvalue())
    print(f"{out}: {frames} frames, {os.path.getsize(out) // 1024} KiB")


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "bootanimation.zip")
