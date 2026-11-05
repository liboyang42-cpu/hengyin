import json, urllib.request, urllib.parse, subprocess, os, sys
B="http://127.0.0.1:8099"; op=urllib.request.build_opener(urllib.request.ProxyHandler({}))
TOK={}
def login(oid):
    r=json.loads(op.open(urllib.request.Request(B+"/api/login/code",data=urllib.parse.urlencode({"code":"rig-"+oid}).encode()),timeout=20).read()); return r["token"], r.get("data")
def tok(who):
    who=str(who)
    if who in ("anon",""): return None
    if who not in TOK:
        oid = ("e2e-openid-"+who) if who.startswith("900") and len(who)==4 else who
        TOK[who]=login(oid)[0]
    return TOK[who]
def call(who,method,path,form=None,js=None,q=None,raw_token=None):
    url=B+path+("?"+urllib.parse.urlencode(q) if q else ""); t=raw_token or tok(who); h={"Authorization":t} if t else {}
    data=None
    if form is not None: data=urllib.parse.urlencode(form).encode(); h["content-type"]="application/x-www-form-urlencoded"
    if js is not None: data=json.dumps(js).encode(); h["content-type"]="application/json"
    if method=="POST" and data is None: data=b""
    try: resp=op.open(urllib.request.Request(url,data=data,headers=h,method=method),timeout=30); st=resp.status; body=resp.read().decode()
    except urllib.error.HTTPError as e: st=e.code; body=e.read().decode()
    try: j=json.loads(body)
    except Exception: j={"raw":body[:300]}
    return st, j
def show(who,method,path,**kw):
    st,j=call(who,method,path,**kw)
    arg=kw.get("js") or kw.get("form") or kw.get("q") or ""
    s=json.dumps(j,ensure_ascii=False)
    print(f"{who} {method} {path} {json.dumps(arg,ensure_ascii=False)[:160]} -> HTTP {st} {s[:360]}"); sys.stdout.flush()
    return st,j
def sql(q):
    return subprocess.run(["bash","-c",". /root/j6/env; sql \"$Q\""],env=dict(os.environ,Q=q),capture_output=True,text=True).stdout.strip()
def mid(who):
    st,j=call(who,"POST","/api/user/info"); d=j.get("data") or {}; return d.get("id") or d.get("memberId")
def buy(who, act, pay=True, name="测试乙", phone="13700000002"):
    call(who,"POST","/api/club/join",js={"id":int(sql(f"select club_id from cms_activity where id={act}"))})
    call(who,"POST","/api/compliance/consents",js={"docType":"activity_host_data_sharing","scene":"activity_signup","eventType":"AGREE","requestId":"j6-consent-%s-%s"%(who,act)})
    tid=int(sql(f"select id from oms_ticket where owner_type=2 and owner_id={act} order by id limit 1"))
    body={"ownerType":2,"ownerId":act,"ticketId":tid,"tickets":[{"ticketId":tid,"count":1}],"realName":name,"phone":phone}
    st,qt=call(who,"POST","/api/registration/quote",js=body)
    if not qt.get("data"): print("quote fail",qt); return None
    body["quoteSign"]=qt["data"]["quoteSign"]; body["requestId"]="j6-reg-%s-%s-%d"%(who,act,os.getpid())
    st,cr=call(who,"POST","/api/registration/create",js=body)
    if not cr.get("data"): print("create fail",cr); return None
    rid=cr["data"]["registrationId"]
    if pay:
        st,p=call(who,"POST","/api/registration/pay",form={"id":rid}); 
        out=subprocess.run([os.environ.get("RIG_HOME","/root/walk-rig")+"/venv/bin/python","/root/rig/docs/delivery/cloud-walk-20261101/rig/pay.py","--reg-no",cr["data"]["registrationNo"],"--json"],capture_output=True,text=True).stdout.strip().split("\n")[-1]
        print("paid", rid, out[:200])
    return rid
