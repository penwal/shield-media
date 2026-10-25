"""Renders the Android TV launcher banner (320x180 @xhdpi) and app icon from the Inter font.

Run: python tools/brand/make_banner.py   (needs Pillow)
"""
import pathlib
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = pathlib.Path(__file__).resolve().parents[2]
FONT = ROOT / "core/designsystem/src/main/res/font/inter.ttf"
RES = ROOT / "app/src/main/res"
BG_TOP, BG_BOTTOM = (20, 22, 28), (8, 9, 12)
GOLD = (240, 180, 41)
WHITE = (242, 243, 245)


def font(size, weight):
    f = ImageFont.truetype(str(FONT), size)
    # Axis order follows the font file: Inter[opsz,wght].
    axes = [a["name"] for a in f.get_variation_axes()]
    values = {b"Optical size": 32, "Optical size": 32, b"Weight": weight, "Weight": weight}
    f.set_variation_by_axes([values.get(a, 400) for a in axes])
    return f


def background(w, h):
    img = Image.new("RGB", (w, h))
    d = ImageDraw.Draw(img)
    for y in range(h):
        t = y / max(1, h - 1)
        d.line([(0, y), (w, y)], fill=tuple(int(a + (b - a) * t) for a, b in zip(BG_TOP, BG_BOTTOM)))
    # soft warm glow behind the mark
    glow = Image.new("RGB", (w, h), (0, 0, 0))
    gd = ImageDraw.Draw(glow)
    gd.ellipse([-w * 0.2, h * 0.15, w * 0.55, h * 1.2], fill=(70, 50, 12))
    glow = glow.filter(ImageFilter.GaussianBlur(h * 0.35))
    return Image.blend(img, Image.composite(glow, img, Image.new("L", (w, h), 120)), 0.6)


def play_mark(img, cx, cy, r):
    """Gold play triangle with softly rounded corners, drawn 4x and downsampled for clean edges."""
    s = 4
    size = int(r * 2.4 * s)
    layer = Image.new("L", (size, size), 0)
    d = ImageDraw.Draw(layer)
    c = size / 2
    rr = r * s
    pts = [(c - rr * 0.55, c - rr * 0.8), (c - rr * 0.55, c + rr * 0.8), (c + rr * 0.85, c)]
    d.polygon(pts, fill=255)
    layer = layer.filter(ImageFilter.GaussianBlur(rr * 0.06)).point(lambda v: 255 if v > 128 else 0)
    layer = layer.resize((size // s, size // s), Image.LANCZOS)
    gold = Image.new("RGB", layer.size, GOLD)
    img.paste(gold, (int(cx - layer.size[0] / 2), int(cy - layer.size[1] / 2)), layer)


def banner(scale):
    w, h = 320 * scale, 180 * scale
    img = background(w, h)
    d = ImageDraw.Draw(img)
    play_mark(img, w * 0.155, h * 0.5, h * 0.14)
    title = font(int(h * 0.165), 700)
    sub = font(int(h * 0.075), 500)
    d.text((w * 0.255, h * 0.53), "Shield Media", font=title, fill=WHITE, anchor="lb")
    d.text((w * 0.258, h * 0.6), "PLEX  ·  LIVE TV", font=sub, fill=(163, 169, 181), anchor="lt")
    return img


def icon(size):
    img = background(size, size)
    d = ImageDraw.Draw(img)
    play_mark(img, size * 0.52, size * 0.5, size * 0.26)
    mask = Image.new("L", (size, size), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, size - 1, size - 1], radius=size * 0.22, fill=255)
    out = Image.new("RGBA", (size, size))
    out.paste(img, (0, 0), mask)
    return out


for density, scale in [("xhdpi", 2), ("xxhdpi", 3), ("xxxhdpi", 4)]:
    folder = RES / f"drawable-{density}"
    folder.mkdir(parents=True, exist_ok=True)
    banner(scale).save(folder / "banner.png", optimize=True)
for density, px in [("mdpi", 48), ("hdpi", 72), ("xhdpi", 96), ("xxhdpi", 144), ("xxxhdpi", 192)]:
    folder = RES / f"mipmap-{density}"
    folder.mkdir(parents=True, exist_ok=True)
    icon(px).save(folder / "ic_launcher.png", optimize=True)
print("written to", RES)
