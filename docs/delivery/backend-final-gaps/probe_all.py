import sys, json, time; sys.path.insert(0, "/data/workspace/be-int/final-gaps")
from probe_lib import *
D="/data/workspace/be-int/final-gaps/"
match=json.load(open(D+"match.json")); appv=json.load(open(D+"appverbs.json"))
routes=json.load(open(D+"backend.json"))["routes"]
tok={"merchant":login("13900000002")}
CLOSED=("DISABLED","NOT_ENABLED","CLOSED","FEATURE_OFF","NOT_OPEN","UNAVAILABLE_IN","RELEASE_OFF","NOT_SUPPORTED")
def fill(p): return p.replace("{}","999999999").replace("**","x")
def probe(v,p):
    st,d=call(v,fill(p),tok["merchant"],body={} if v in("POST","PUT","PATCH") else None)
    ec=str(d.get("errorCode") or ""); msg=str(d.get("msg") or d.get("raw") or "")[:80]
    if st==404 and d.get("code")==404 and "请求路径不存在" in msg: k="404"
    elif st==405 or "不支持" in msg and "请求方法" in msg: k="405"
    elif st in(501,503) or d.get("code") in (501,503) or any(c in (ec+" "+msg).upper() for c in CLOSED): k="closed"
    else: k="served"
    return {"verb":v,"url":fill(p),"http":st,"code":d.get("code"),"errorCode":ec,"msg":msg,"k":k}
def verbs_of(r): return [x for x in r if x!="ANY"] or ["GET","POST"]
rows=[]
for o in match:
    p=o["path"]; av=sorted(appv.get(p,{}).get("verbs",{}),key=lambda x:-appv[p]["verbs"][x])
    res=[]; kind="exact"
    if o["exact"]:
        for bp,bv,ctl in o["exact"]:
            vs=verbs_of(bv)
            for v in vs: res.append(dict(probe(v,bp),route=bp,ctl=ctl))
            for v in av[:1]:
                if v not in vs: res.append(dict(probe(v,bp),route=bp+" (app verb)",ctl=ctl))
    else:
        bare=p.rstrip("/")
        kids=[r for r in routes if r["path"].startswith(bare+"/")]
        if kids:
            kind="prefix"
            for r in kids[:60]:
                for v in verbs_of(r["verbs"])[:2]: res.append(dict(probe(v,r["path"]),route=r["path"],ctl=r["controller"]+"."+r["method"]))
            res.append(dict(probe(av[0] if av else "GET",bare),route="(prefix itself)",ctl=""))
        else:
            kind="none"
            for v in (av[:2] or ["GET","POST"]): res.append(dict(probe(v,p),route="",ctl=""))
    rows.append({"path":p,"kind":kind,"appVerbs":av,"appHits":appv.get(p,{}).get("hits",[])[:3],"probes":res})
json.dump(rows,open(D+"probe.json","w"),ensure_ascii=False,indent=1)
for r in rows:
    ks=[x["k"] for x in r["probes"] if x["route"]!="(prefix itself)"]
    print(r["kind"],r["path"],"app=",",".join(r["appVerbs"][:2]),"|"," ".join("%s %s→%s/%s%s"%(x["verb"],x["route"] or x["url"],x["http"],x["code"],(" "+x["errorCode"]) if x["errorCode"] else "") for x in r["probes"][:4]))
