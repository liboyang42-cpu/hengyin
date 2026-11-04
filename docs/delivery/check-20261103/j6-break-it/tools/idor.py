import json, urllib.request, urllib.parse, sys
B="http://127.0.0.1:8099"; op=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def login(oid):
    r=json.loads(op.open(urllib.request.Request(B+"/api/login/code",data=urllib.parse.urlencode({"code":"rig-"+oid}).encode()),timeout=20).read()); return r["token"]
def call(tok,method,path,form=None,js=None,q=None):
    url=B+path+("?"+urllib.parse.urlencode(q) if q else ""); h={"Authorization":tok} if tok else {}
    data=None
    if form is not None: data=urllib.parse.urlencode(form).encode(); h["content-type"]="application/x-www-form-urlencoded"
    if js is not None: data=json.dumps(js).encode(); h["content-type"]="application/json"
    try: resp=op.open(urllib.request.Request(url,data=data,headers=h,method=method),timeout=20); st=resp.status; body=resp.read().decode()
    except urllib.error.HTTPError as e: st=e.code; body=e.read().decode()
    return st, body
T={k:login("e2e-openid-"+k) for k in ["9002","9003","9004","9005","9008","9009"]}
json.dump(T,open("/root/j6/tokens.json","w"))
if __name__=="__main__":
    for line in open(sys.argv[1]):
        line=line.strip()
        if not line or line.startswith("#"): print(line); continue
        who,method,path,kind,arg=line.split(" ",4); a=json.loads(arg)
        st,body=call(T.get(who) if who!="anon" else None,method,path,**({kind:a} if kind!="none" else {}))
        print(f"{who} {method} {path} {arg} -> HTTP {st} {body[:300]}")
