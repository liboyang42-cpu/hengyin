import json, sys, os, io, collections
from PIL import Image, ImageDraw, ImageFont
name = sys.argv[1]; D = 'out/' + name; m = json.load(open(D + '/m.json'))
RPX = 750 / 390
F = ImageFont.truetype('/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf', 11)
RED = (235, 40, 40); GRN = (20, 190, 80); YEL = (240, 190, 0)
band = m['band']; scr = m['screens']; top, bot = max(band['top'], 88), band['bot']
last = scr[-1]['S']
H = bot + last + (844 - bot)
img = Image.new('RGB', (390, H), (0, 0, 0))
first = Image.open(D + '/' + scr[0]['file']).convert('RGB')
img.paste(first.crop((0, 0, 390, bot)), (0, 0))
for s in scr[1:]:
    im = Image.open(D + '/' + s['file']).convert('RGB')
    img.paste(im.crop((0, top, 390, bot)), (0, top + s['S']))
lastim = Image.open(D + '/' + scr[-1]['file']).convert('RGB')
img.paste(lastim.crop((0, bot, 390, 844)), (0, bot + last))
dr = ImageDraw.Draw(img)
def Y(y, fixed=False):
    if fixed and y >= bot: return y + last
    return y
def mode(vals):
    c = collections.Counter(round(v) for v in vals); return c.most_common(1)[0][0] if c else None
def col(v, md): return GRN if md is None or abs(v - md) < 2 else RED
def label(x, y, text, c):
    w = dr.textlength(text, font=F) + 6
    x = max(0, min(390 - w, x))
    dr.rectangle([x, y - 7, x + w, y + 7], fill=c); dr.text((x + 3, y - 6), text, fill=(255, 255, 255), font=F)
fmt = lambda v: ('%g' % round(v)) if abs(v - round(v)) < .05 else '%.1f' % v
sel = json.load(open(D + '/sel.json')) if os.path.exists(D + '/sel.json') else {}
# stacks: choose which to annotate
stk = [s for s in m['stacks'] if not s['fixed']]
want = sel.get('stacks')
if want is None:
    stk.sort(key=lambda s: -(s['items'][-1]['b'] - s['items'][0]['t']))
    chosen = stk[:1]
    for s in stk[1:]:
        roles = collections.Counter(i['role'] for i in s['items'])
        if roles.most_common(1)[0][1] >= 3 and len(chosen) < 4: chosen.append(s)
else:
    chosen = [s for s in stk if s['parent'] in want]
for si, s in enumerate(chosen):
    md = mode(s['gaps']) if len(s['gaps']) >= 3 else None
    xs = 372 - 26 * si
    for i, g in enumerate(s['gaps']):
        a, b = s['items'][i]['b'], s['items'][i + 1]['t']
        if g < 1: continue
        c = col(g, md) if md is not None else (GRN if max(s['gaps']) - min(s['gaps']) < 2 else (RED if abs(g - mode(s['gaps'])) >= 2 else GRN))
        dr.line([xs, a, xs, b], fill=c, width=2); dr.line([xs - 4, a, xs + 4, a], fill=c, width=2); dr.line([xs - 4, b, xs + 4, b], fill=c, width=2)
        label(xs - 40, (a + b) / 2, fmt(g), c)
# cards
cards = [c for c in m['cards'] if ((not c['fixed']) or (0 <= c['t'] < 844 and c['b'] <= 845)) and c['radius'] < c['h'] / 2 - 1]
byrole = collections.defaultdict(list)
for c in cards: byrole[c['role']].append(c)
for r, cs in byrole.items():
    mr = mode([c['radius'] for c in cs]); ml = mode([c['l'] for c in cs]); mR = mode([c['r'] for c in cs])
    for k, c in enumerate(cs):
        y0 = Y(c['t'], c['fixed']); y1 = Y(c['b'], c['fixed'])
        rc = col(c['radius'], mr)
        if k == 0 or rc == RED:
            dr.arc([c['l'], y0, c['l'] + 2 * max(c['radius'], 3), y0 + 2 * max(c['radius'], 3)], 180, 270, fill=YEL, width=2)
            label(c['l'] + 4, y0 + 9, 'r' + fmt(c['radius']), rc)
        for side, v, mv in (('l', c['l'], ml), ('r', c['r'], mR)):
            cc = col(v, mv)
            if (k == 0 or cc == RED) and v > 0.5 and v < 120 and c['w'] > 234:
                ym = y0 + min(30, (y1 - y0) / 2)
                if side == 'l': dr.line([0, ym, c['l'], ym], fill=cc, width=2); label(1, ym - 10, fmt(v), cc)
                else: dr.line([390 - v, ym, 390, ym], fill=cc, width=2); label(390 - 30, ym - 10, fmt(v), cc)
# titles
tl = [t for t in m['titles'] if not t['fixed'] and 0 <= t['gap'] < 80]
tby = collections.defaultdict(list)
for t in tl: tby[t['role']].append(t)
for r, ts in tby.items():
    md = mode([t['gap'] for t in ts])
    for t in ts:
        if t['gap'] < 1: continue
        c = col(t['gap'], md) if len(ts) > 1 else GRN
        x = t['l'] + 2
        dr.line([x, t['b'], x, t['nt']], fill=c, width=1)
        label(x + 3, (t['b'] + t['nt']) / 2, 't' + fmt(t['gap']), c)
# write, cap size
out = sys.argv[2]
q = img.quantize(colors=96, method=Image.Quantize.FASTOCTREE, dither=Image.Dither.NONE)
scale = 1.0
while True:
    b = io.BytesIO(); (q if scale == 1 else img.resize((int(390 * scale), int(H * scale)), Image.LANCZOS).quantize(colors=96, method=Image.Quantize.FASTOCTREE, dither=Image.Dither.NONE)).save(b, 'PNG', optimize=True)
    if b.tell() < 295000 or scale < .4: break
    scale -= .1
open(out, 'wb').write(b.getvalue()); print(out, b.tell(), 'scale', round(scale, 2), 'H', H)
