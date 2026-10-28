#!/usr/bin/env python3
"""Generate AMretro's original desktop icon. Requires Pillow."""
from pathlib import Path
from PIL import Image, ImageDraw

out = Path(__file__).resolve().parents[1] / 'src-tauri' / 'icons'
out.mkdir(parents=True, exist_ok=True)
scale = 3
n = 512 * scale
mask = Image.new('L', (n, n), 0)
m = ImageDraw.Draw(mask)
m.rounded_rectangle((8*scale, 8*scale, 504*scale, 504*scale), radius=110*scale, fill=255)
base = Image.new('RGBA', (n, n), (0,0,0,0))
color = Image.new('RGBA', (n,n))
pixels = color.load()
for y in range(n):
    for x in range(n):
        t = min(1, (x + y) / (2*n))
        pixels[x,y] = (int(32 - 23*t), int(69 - 43*t), int(89 - 55*t), 255)
base.paste(color, (0,0), mask)
d = ImageDraw.Draw(base)
d.rounded_rectangle((12*scale,12*scale,500*scale,500*scale),radius=108*scale,outline=(116,218,233,105),width=3*scale)
# Distinctive geometric A; the lower line evokes a pixel-era console.
poly=[(93,334),(218,101),(282,101),(420,334),(350,334),(325,285),(184,285),(160,334)]
d.polygon([(x*scale,y*scale) for x,y in poly],fill=(114,224,239,255))
d.polygon([(250*scale,173*scale),(207*scale,250*scale),(294*scale,250*scale)],fill=(19,48,63,255))
d.rounded_rectangle((186*scale,371*scale,316*scale,385*scale),radius=7*scale,fill=(114,224,239,255))
img=base.resize((512,512),Image.Resampling.LANCZOS)
img.save(out/'icon.png', optimize=True)
img.save(out/'icon.ico', sizes=[(16,16),(24,24),(32,32),(48,48),(64,64),(128,128),(256,256)])
print('Wrote', out/'icon.png', 'and', out/'icon.ico')
