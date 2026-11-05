# Build the per-page table from logs: guest (G-), new player (N-), active player sweep (A-). Result/notes come from RES.
import json, os, re, sys
L = os.path.join(os.path.dirname(__file__), '..', 'logs')
all_ = json.load(open(os.path.join(os.path.dirname(__file__), 'A-all.json')))
RES = json.load(open(os.path.join(os.path.dirname(__file__), 'results.json')))
def peek(pre, pid):
    f = os.path.join(L, '%s-%s.json' % (pre, pid))
    if not os.path.exists(f): return '—'
    d = json.load(open(f)); t = d['text'].split(' | 9:41')[0]
    t = re.sub(r'\s+', ' ', t)
    if d['route'][-1].split('?')[0] != d['url'].split('?')[0]: t = '→ ' + d['route'][-1] + ' · ' + t
    return t[:70].replace('|', '/')
print('| # | Page | Reached as active player (9009) | Guest | New player 990005 | Active player 9009 | Controls tapped / no visible effect | Result | Shots |')
print('|---|---|---|---|---|---|---|---|---|')
for i, c in enumerate(all_, 1):
    pid = c['id'][2:]
    a = json.load(open(os.path.join(L, c['id'] + '.json')))
    t = re.sub(r'\s+', ' ', a['text'].split(' | 9:41')[0])[:70].replace('|', '/')
    tapped = [x for x in a['controls'] if x.get('result') and not x['result'].startswith(('back', 'SKIPPED', 'not reach', 'off-screen'))]
    ne = sum(1 for x in tapped if x['result'] == 'NO VISIBLE EFFECT')
    r = RES.get(pid, ['', ''])
    page = c['path'].split('?')[0]
    print('| %d | `%s` | %s | %s | %s | %s | %d / %d | %s | G-%s, N-%s, A-%s-* %s |' % (i, page, r[1] or ('`' + c['path'] + '`'), peek('G', pid), peek('N', pid), t, len(tapped), ne, r[0], pid, pid, pid, ''))
