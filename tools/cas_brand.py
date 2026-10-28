"""Regenerate the CAS launcher assets with Pillow (Inter is licensed in assets/fonts).

Run from anywhere: python3 tools/cas_brand.py

- banner.webp (320x180): the Fire TV / Android TV launcher tile. A quiet charcoal gradient, not flat black, so the
  tile has depth beside the bright tiles around it, with the wordmark held well inside the edges.
- logo_transparent.png (512x512): the bare wordmark; the adaptive icon's foreground and the Watch Next mark.
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[1]
FONT = ROOT / 'app/src/main/assets/fonts/inter.ttf'
DEST = ROOT / 'app/src/main/res/drawable-nodpi'

BASE = (8, 9, 11)        # #08090B, Screen.BASE
LIFT = (30, 32, 38)      # the top of the gradient: barely lighter, so the tile reads as a surface, not a hole
INK = (245, 245, 247)    # #F5F5F7, Screen.TEXT


def plate(size):
    """Vertical charcoal gradient, LIFT at the top to BASE at the bottom."""
    w, h = size
    im = Image.new('RGB', size, BASE)
    px = im.load()
    for y in range(h):
        t = y / max(1, h - 1)
        t = t * t  # ease: most of the lift stays near the top
        row = tuple(round(LIFT[i] + (BASE[i] - LIFT[i]) * t) for i in range(3))
        for x in range(w):
            px[x, y] = row
    return im.convert('RGBA')


def wordmark(draw, size, font_size, tracking=0.14, weight=650, fill=INK):
    """'CAS' set in Inter, letterspaced and optically centred (the cap height, not the em box)."""
    font = ImageFont.truetype(str(FONT), font_size)
    font.set_variation_by_axes([32, weight])  # Inter's axes are [optical size, weight]; 32 is its display cut
    gap = font_size * tracking
    widths = [draw.textlength(c, font=font) for c in 'CAS']
    total = sum(widths) + gap * 2
    x = (size[0] - total) / 2
    box = draw.textbbox((0, 0), 'CAS', font=font)
    y = (size[1] - (box[3] - box[1])) / 2 - box[1]
    for c, width in zip('CAS', widths):
        draw.text((x, y), c, fill=fill, font=font)
        x += width + gap


def mark(size, font_size, transparent=False):
    im = Image.new('RGBA', size, (0, 0, 0, 0)) if transparent else plate(size)
    wordmark(ImageDraw.Draw(im), size, font_size)
    return im


mark((320, 180), 58).convert('RGB').save(DEST / 'banner.webp', lossless=True)
mark((512, 512), 150, transparent=True).save(DEST / 'logo_transparent.png')
print('wrote', sorted(p.name for p in DEST.iterdir() if p.stem in ('banner', 'logo_transparent')))
