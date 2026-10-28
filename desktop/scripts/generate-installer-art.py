#!/usr/bin/env python3
"""Create original NSIS sidebar art from AMretro's bundled hero image."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageEnhance, ImageFont, ImageOps

here = Path(__file__).resolve().parents[1]
hero = Image.open(here / 'ui/assets/retro-hero.jpg').convert('RGB')
# NSIS classic wizard image: 164x314 pixels, saved as a 24-bit BMP.
canvas = ImageOps.fit(hero, (328, 628), method=Image.Resampling.LANCZOS, centering=(.28, .5))
canvas = ImageEnhance.Brightness(canvas).enhance(.58).convert('RGBA')
overlay = Image.new('RGBA', canvas.size, (0, 0, 0, 0))
d = ImageDraw.Draw(overlay)
for y in range(628):
    top = max(0, 1-y/190)
    bottom = max(0, (y-360)/268)
    alpha = round(210*top + 165*bottom)
    d.line((0,y,328,y), fill=(4,15,24,min(235,alpha)))
canvas = Image.alpha_composite(canvas, overlay)
d = ImageDraw.Draw(canvas)
font = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', 34)
small = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', 14)
d.rounded_rectangle((24,24,82,82),radius=15,fill=(21,61,77,230),outline=(108,222,237,180),width=2)
d.polygon([(34,70),(48,40),(58,40),(74,70),(65,70),(62,63),(46,63),(43,70)],fill=(115,224,238))
d.polygon([(53,49),(49,58),(57,58)],fill=(20,50,64))
d.text((94,31), 'AMretro', font=font, fill=(244,251,252))
d.text((26,552), 'PLAY IT AGAIN', font=small, fill=(167,226,235), stroke_width=0)
d.line((26,578,116,578),fill=(108,222,237,220),width=3)
canvas.convert('RGB').resize((164,314),Image.Resampling.LANCZOS).save(here/'installer/sidebar.bmp')
print('Wrote AMretro installer sidebar')
