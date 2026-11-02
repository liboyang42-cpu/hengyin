import json, sys, os
from PIL import Image
spec = json.loads(sys.argv[1])
T, B = int(round(spec['T'])), int(round(spec['B']))
ch = spec['chunks']
maxs = ch[-1]['s']
H = 844 + int(maxs)
out = Image.new('RGB', (390, H), 'white')
for i, c in enumerate(ch):
    im = Image.open(c['file']).convert('RGB')
    s = int(round(c['s']))
    y0 = 0 if i == 0 else T
    y1 = B if i < len(ch) - 1 else 844
    out.paste(im.crop((0, y0, 390, y1)), (0, y0 + s))
    if i == len(ch) - 1 and i > 0:
        out.paste(im.crop((0, 0, 390, T)), (0, 0))  # keep header from first? no: header from first chunk
first = Image.open(ch[0]['file']).convert('RGB')
out.paste(first.crop((0, 0, 390, T)), (0, 0))
p = spec['out']
for colors in (128, 64, 32):
    q = out.quantize(colors=colors, method=Image.Quantize.MEDIANCUT, dither=Image.Dither.NONE)
    q.save(p, optimize=True)
    if os.path.getsize(p) < 290 * 1024:
        break
print(p, os.path.getsize(p), H)
