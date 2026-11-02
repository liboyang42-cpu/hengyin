# python3 brand.py <measure-json-dir> <xcx-root> : one row per page: inset, card radii, section gaps, title->first block, press classes (static WXML)
import json, glob, os, re, sys, collections
d, xcx = sys.argv[1], sys.argv[2]
def press(route):
    f = os.path.join(xcx, route + '.wxml')
    if not os.path.exists(f): return ''
    s = open(f, encoding='utf8').read()
    c = collections.Counter(re.findall(r'hover-class="([^"{]*)"', s))
    dyn = len(re.findall(r'hover-class="\{\{', s))
    taps = len(re.findall(r'\b(bind|catch):?tap=', s))
    parts = ['%s×%d' % (k, v) for k, v in c.most_common()]
    if dyn: parts.append('dyn×%d' % dyn)
    return 'taps %d: ' % taps + ', '.join(parts)
mode = lambda a: collections.Counter(a).most_common(1)[0][0] if a else None
for f in sorted(glob.glob(d + '/*.json')):
    j = json.load(open(f)); m = j.get('m') or {}
    if not m: print('|', j['name'], '| no measure |'); continue
    secs = m.get('secs', []); gaps = m.get('gaps', [])
    tg = None
    for i, s in enumerate(secs[:-1]):
        if re.search(r'pt__title|page-title', s) and i < len(gaps): tg = gaps[i]; break
    lefts = m.get('lefts', []) + m.get('textLeft', [])
    inset = mode([round(x, 1) for x in lefts if x > 1])
    rad = sorted(set(r for r in m.get('radii', []) if r > 0))
    body = [g for i, g in enumerate(gaps) if g > 0 and not (tg is not None and i == [k for k, s in enumerate(secs) if re.search(r'pt__title|page-title', s)][0])]
    print('| %s | %s | %s | %s | %s | %s | %s |' % (j['name'], j.get('route', ''), inset, '/'.join(map(str, rad)) or '—', '/'.join(map(str, body[:6])) or '—', tg if tg is not None else '—', press(j.get('route', ''))))
