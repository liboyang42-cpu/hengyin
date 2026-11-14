import json,re,sys
D='/data/workspace/be-int/final-gaps/'
routes=json.load(open(D+'backend.json'))['routes']
def segs(p): return [s for s in p.strip('/').split('/')]
def match(app,be):
    a,b=segs(app),segs(be)
    if len(a)!=len(b): return False
    return all(x==y or x=='{}' or y=='{}' for x,y in zip(a,b))
out=[]
for line in open(D+'missing.txt'):
    p=line.strip()
    if not p: continue
    exact=[] if p.endswith('/') else [(r['path'],r['verbs'],r['controller']+'.'+r['method']) for r in routes if match(p,r['path'])]
    lit=[e for e in exact if e[0]==p]
    if lit: exact=lit
    pref=[r['path'] for r in routes if r['path'].startswith(p.rstrip('/')+'/')] if not exact else []
    out.append({'path':p,'exact':exact,'prefix':pref[:6],'nprefix':len(pref)})
json.dump(out,open(D+'match.json','w'),ensure_ascii=False,indent=1)
n=sum(1 for o in out if o['exact']); m=sum(1 for o in out if not o['exact'] and o['nprefix']); print('total',len(out),'exact',n,'prefixonly',m,'none',len(out)-n-m)
for o in out:
    if not o['exact']: print('NOEXACT',o['path'],o['nprefix'],o['prefix'][:3])
