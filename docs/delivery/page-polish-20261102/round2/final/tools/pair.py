# python3 pair.py <before.png> <after.png> <out.png> [label] [crop "y0,y1"]
# before | after side by side, labelled, palette-quantised to stay under 300 KB.
import sys, os
from PIL import Image, ImageDraw, ImageFont
b, a, out = sys.argv[1:4]
label = sys.argv[4] if len(sys.argv) > 4 else ''
crop = [int(x) for x in sys.argv[5].split(',')] if len(sys.argv) > 5 and sys.argv[5] else None
imgs = []
for p in (b, a):
    im = Image.open(p).convert('RGB') if p != '-' else Image.new('RGB', (390, 844), (128, 128, 128))
    if crop: im = im.crop((0, crop[0], im.width, crop[1]))
    imgs.append(im)
H = 22
W = imgs[0].width + imgs[1].width + 6
canvas = Image.new('RGB', (W, max(i.height for i in imgs) + H), (40, 40, 40))
canvas.paste(imgs[0], (0, H)); canvas.paste(imgs[1], (imgs[0].width + 6, H))
d = ImageDraw.Draw(canvas)
try: f = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf', 13)
except Exception: f = ImageFont.load_default()
d.text((6, 4), os.environ.get('LEFT', 'BEFORE (master c6eac3b8)'), fill=(255, 255, 255), font=f)
d.text((imgs[0].width + 12, 4), os.environ.get('RIGHT', 'AFTER (r2-preview a7c3624c) ' + label), fill=(255, 255, 255), font=f)
for colors in (256, 128, 64):
    canvas.quantize(colors=colors, method=Image.Quantize.MEDIANCUT).save(out, optimize=True)
    if os.path.getsize(out) < 290000: break
print(out, os.path.getsize(out))
