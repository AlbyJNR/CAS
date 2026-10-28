"""Regenerate CAS launcher assets with Pillow (Inter is licensed in assets/fonts)."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
ROOT = Path(__file__).resolve().parents[1]
FONT = ROOT / 'app/src/main/assets/fonts/inter.ttf'
DEST = ROOT / 'app/src/main/res/drawable-nodpi'

def mark(size, font_size, transparent=False):
    im = Image.new('RGBA', size, (0, 0, 0, 0) if transparent else (8, 9, 11, 255))
    font = ImageFont.truetype(str(FONT), font_size)
    font.set_variation_by_axes([600])
    draw = ImageDraw.Draw(im)
    gap = font_size * .12
    widths = [draw.textlength(c, font=font) for c in 'CAS']
    x = (size[0] - sum(widths) - gap * 2) / 2
    box = draw.textbbox((0, 0), 'CAS', font=font)
    y = (size[1] - (box[3] - box[1])) / 2 - box[1]
    for c, width in zip('CAS', widths):
        draw.text((x, y), c, fill='#f5f5f7', font=font)
        x += width + gap
    return im

mark((512, 512), 146).save(DEST / 'logo.webp', lossless=True)
mark((320, 180), 66).save(DEST / 'banner.webp', lossless=True)
mark((512, 512), 146, True).save(DEST / 'logo_transparent.png')
