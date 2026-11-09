import os, sys, json, urllib.request, urllib.parse
RIG = '/root/walk-rig'
sys.path.insert(0, RIG + '/candidate/scripts/e2e')
for k in ("HTTP_PROXY","HTTPS_PROXY","http_proxy","https_proxy"): os.environ.pop(k, None)
os.environ['PATH'] = RIG + '/work/bin:' + os.environ['PATH']
import chain_lib as cl
from chain_player import store_trusted_payment_fact
def login(openid):
    data = urllib.parse.urlencode({"code": "rig-" + openid}).encode()
    req = urllib.request.Request("http://127.0.0.1:8099/api/login/code", data=data, method="POST")
    r = json.loads(urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=20).read().decode())
    return r.get("token"), (r.get("data") or {})
