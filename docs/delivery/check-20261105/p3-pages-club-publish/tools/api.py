import sys,json,urllib.request,urllib.parse
op=urllib.request.build_opener(urllib.request.ProxyHandler({}))
def login(oid):
    r=json.loads(op.open(urllib.request.Request("http://127.0.0.1:8099/api/login/code",data=urllib.parse.urlencode({"code":"rig-"+oid}).encode()),timeout=20).read());return r["token"]
def call(tok,method,path,body=None):
    data=None;h={"Authorization":"Bearer "+tok}
    if method=="GET" and body: path+="?"+urllib.parse.urlencode(body)
    elif body is not None: data=json.dumps(body).encode();h["content-type"]="application/json"
    try: return json.loads(op.open(urllib.request.Request("http://127.0.0.1:8099"+path,data=data,method=method,headers=h),timeout=30).read())
    except urllib.error.HTTPError as e: return {"http":e.code,"body":e.read().decode()[:300]}
if __name__=="__main__":
    oid,method,path=sys.argv[1:4];body=json.loads(sys.argv[4]) if len(sys.argv)>4 else None
    print(json.dumps(call(login(oid),method,path,body),ensure_ascii=False)[:3000])
