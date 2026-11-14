import json
from collections import Counter
D="/data/workspace/be-int/final-gaps/"
rows=json.load(open(D+"probe.json")); c=Counter()
for r in rows:
    ps=[x for x in r["probes"] if x["route"]!="(prefix itself)" and not x["route"].endswith("(app verb)")]
    ks=[x["k"] for x in ps]
    if all(k=="404" for k in ks): st="404"
    elif "served" in ks: st="served"
    elif "closed" in ks: st="closed"
    else: st="other"
    av=[x for x in r["probes"] if x["route"].endswith("(app verb)")]
    r["st"]=st; r["verbflag"]=[(x["verb"],x["route"],x["http"],x["code"],x["msg"][:40]) for x in av if x["k"] in("404","405")]
    c[st]+=1
    if st!="served" or r["verbflag"] or "404" in ks or "405" in ks:
        print(st,r["kind"],r["path"],"| nonserved:",[(x["verb"],x["route"],x["http"],x["code"],x["errorCode"] or x["msg"][:30]) for x in ps if x["k"]!="served"][:3],"| appverb:",r["verbflag"][:2])
print(c)
json.dump(rows,open(D+"probe.json","w"),ensure_ascii=False,indent=1)
