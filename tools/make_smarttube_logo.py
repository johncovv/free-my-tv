"""Draws assets/logos/smarttube.png: a red play button, "YouTube" and a spaced-out "LITE" (transparent background)."""
import os
import sys

from PIL import Image, ImageDraw, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FONT = os.path.join(ROOT, "app", "assets", "fonts", "InterVariable.ttf")
OUT = os.path.join(ROOT, "app", "assets", "logos", "smarttube.png")

S = 4  # drawn 4x larger, then scaled down (smooth edges)


def font(size, weight):
    f = ImageFont.truetype(FONT, size * S)
    try:
        f.set_variation_by_axes([14, weight] if len(f.get_variation_axes()) == 2 else [weight])
    except (OSError, AttributeError):
        pass
    return f


big, small = font(120, 700), font(54, 500)
bw, bh, gap = 210, 150, 44
tw = max(big.getlength("YouTube"), small.getlength("L I T E")) / S
W, H = int(bw + gap + tw + 8), 190
img = Image.new("RGBA", (W * S, H * S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
top = (H - bh) / 2
d.rounded_rectangle([0, top * S, bw * S, (top + bh) * S], radius=40 * S, fill=(232, 19, 43, 255))
cx, cy, r = bw / 2, top + bh / 2, 34
d.polygon([((cx - r * 0.8) * S, (cy - r) * S), ((cx - r * 0.8) * S, (cy + r) * S), ((cx + r * 1.1) * S, cy * S)],
          fill=(255, 255, 255, 255))
tx = (bw + gap) * S
d.text((tx, (cy - 6) * S), "YouTube", font=big, fill=(255, 255, 255, 255), anchor="ls")
d.text((tx + 4 * S, (cy + 62) * S), "L I T E", font=small, fill=(255, 255, 255, 190), anchor="ls")
img = img.resize((W, H), Image.LANCZOS)
img.save(OUT)
print("wrote", OUT, img.size, file=sys.stderr)
