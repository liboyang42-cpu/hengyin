import subprocess,re,sys,json
B='c6eac3b8c'
lanes=[('pay-gate','8712fa452'),('l1-play','14312f809'),('l2-explore','6fbde34a1'),('l3-sell','6621c8672'),('l4-counter','48bc8a920'),('l5-onboard','d0383fb43'),('l6-club','ef03e7a3b'),('l7-launch','8ecb80e56'),('l8-safety','bc86e52a6'),('#710','beec06ee8'),('#719','a7c3624cf')]
def g(*a): return subprocess.check_output(['git',*a],stderr=subprocess.DEVNULL).decode('utf-8','replace')
def show(rev,p):
    try: return g('show',f'{rev}:{p}')
    except subprocess.CalledProcessError: return None
def count(p,src):
    if src is None: return None
    if p.endswith('.java'): return len(re.findall(r'@(Test|ParameterizedTest|RepeatedTest|TestFactory)\b',src))
    if p.endswith('.py'): return len(re.findall(r'^\s*def test_',src,re.M))
    return len(re.findall(r'(?<![\w.])(test|it)\s*\(\s*[\'"`]',src))
out={}
for name,c in lanes:
    mb=g('merge-base',B,c).strip()
    files=[f for f in g('diff','--name-only','--diff-filter=AMR',mb,c).split() if re.search(r'(Test|Tests|IT)\.java$|\.test\.(js|cjs|mjs)$|/test_[^/]+\.py$',f)]
    rows=[];drop=[];weaker=[]
    lt=ht=0
    for f in files:
        a=count(f,show(c,f)); h=count(f,show('HEAD',f))
        if h is None:
            # renamed?
            drop.append(f); continue
        lt+=a or 0; ht+=h or 0
        if h<(a or 0): weaker.append((f,a,h))
    out[name]=dict(files=len(files),lane=lt,merged=ht,missing=drop,fewer=weaker)
    print(f"{name}: {len(files)} test files, lane {lt} tests, merged {ht}; missing {drop}; fewer {weaker}")
json.dump(out,open(sys.argv[1],'w'),ensure_ascii=False,indent=1)
