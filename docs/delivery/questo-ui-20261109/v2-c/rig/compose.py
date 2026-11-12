# 把原型帧与真机(rig)帧按时间戳并排拼成一张:左 = 原型,右 = 小程序。python3 compose.py <protoDir> <rigDir> <outDir> <m> <look>
import sys, glob, os, re
from PIL import Image, ImageDraw
pd, rd, od, m, look = sys.argv[1:6]
os.makedirs(od, exist_ok=True)
ts = sorted(int(re.search(r'-(\d{5})\.png', f).group(1)) for f in glob.glob(f'{pd}/p-m{m}-{look}-*.png'))
tiles = []
for t in ts:
    a = Image.open(f'{pd}/p-m{m}-{look}-{t:05d}.png').convert('RGB')
    rf = f'{rd}/r-m{m}-{look}-{t:05d}.png'
    if not os.path.exists(rf): continue
    b = Image.open(rf).convert('RGB').resize((a.width, a.height))
    im = Image.new('RGB', (a.width * 2 + 12, a.height + 28), (30, 30, 34))
    im.paste(a, (0, 28)); im.paste(b, (a.width + 12, 28))
    d = ImageDraw.Draw(im); d.text((6, 8), f'm{m} {look}  t={t}ms   proto | mini-program', fill=(235, 235, 235))
    im.save(f'{od}/m{m}-{look}-{t:05d}.jpg', quality=82); tiles.append(im)
# 总览:每行 4 个时间点缩小
if tiles:
    w, h = tiles[0].size; s = 0.5; tw, th = int(w * s), int(h * s)
    cols = 4; rows = (len(tiles) + cols - 1) // cols
    sheet = Image.new('RGB', (tw * cols, th * rows), (20, 20, 24))
    for i, im in enumerate(tiles): sheet.paste(im.resize((tw, th)), ((i % cols) * tw, (i // cols) * th))
    sheet.save(f'{od}/m{m}-{look}-contact.jpg', quality=85)
print(len(tiles), 'frames')
