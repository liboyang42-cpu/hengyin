import json,sys,collections
for n in sys.argv[1:]:
    m=json.load(open(f'out/{n}/m.json'))
    print('=====',n, 'scH',m['scH'])
    st=[s for s in m['stacks'] if not s['fixed']]
    st.sort(key=lambda s:-(s['items'][-1]['b']-s['items'][0]['t']))
    for s in st[:7]:
        print(' STACK',s['parent'],'gaps',s['gaps'],'|',' '.join(i['role'].split('.')[-1] for i in s['items'])[:160])
    by=collections.defaultdict(list)
    for c in m['cards']:
        if c['fixed'] and not (0<=c['t']<844): continue
        if c['radius']>=c['h']/2-1: continue
        by[c['role']].append(c)
    for r,cs in by.items():
        print(' CARD',r,'n',len(cs),'L',sorted({c['l'] for c in cs}),'R',sorted({c['r'] for c in cs}),'rad',sorted({c['radius'] for c in cs}),'pad',sorted({tuple(c['pad']) for c in cs})[:3],'fx' if cs[0]['fixed'] else '')
    tb=collections.defaultdict(list)
    for t in m['titles']:
        if not t['fixed'] and 0<=t['gap']<80: tb[t['role']+'→'+t['next']].append(t['gap'])
    for k,v in tb.items(): print(' TITLE',k,v)
    L=sorted(((int(k),v) for k,v in m['lefts'].items()),key=lambda x:-x[1])[:6]; print(' TEXT-LEFT',L)
