import os, sys, json, datetime
RIG = os.path.expanduser('~/walk-rig')
sys.path.insert(0, RIG + '/candidate/scripts/e2e')
for k in ("HTTP_PROXY","HTTPS_PROXY","http_proxy","https_proxy"): os.environ.pop(k, None)
os.environ['PATH'] = RIG + '/work/bin:' + os.environ['PATH']
import chain_lib as cl
import urllib.request, urllib.parse
def login(openid):
    data = urllib.parse.urlencode({"code": "rig-" + openid}).encode()
    req = urllib.request.Request("http://127.0.0.1:8099/api/login/code", data=data, method="POST", headers={"content-type": "application/x-www-form-urlencoded"})
    r = json.loads(urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=20).read().decode())
    return r.get("token"), (r.get("data") or {})
def idcard(body17):
    w=[7,9,10,5,8,4,2,1,6,3,7,9,10,5,8,4,2]; c='10X98765432'
    return body17 + c[sum(int(a)*b for a,b in zip(body17,w))%11]
def J(x): return json.dumps(x, ensure_ascii=False)[:600]
def sh_now(): return datetime.datetime.utcnow() + datetime.timedelta(hours=8)
import pathlib
STATE = pathlib.Path(__file__).with_name('fx_state.json')
def state():
    return json.loads(STATE.read_text()) if STATE.exists() else {}
def save(**kw):
    s = state(); s.update(kw); STATE.write_text(json.dumps(s, ensure_ascii=False, indent=1))
