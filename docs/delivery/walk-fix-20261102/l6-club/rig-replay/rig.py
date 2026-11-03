import os, sys, json, datetime, subprocess, urllib.request, urllib.parse
HOME = os.environ['HOME']
RIG = os.environ.get('RIG_HOME', HOME + '/walk-rig')
CAND = os.environ.get('CAND', RIG + '/candidate')
for k, v in {"CHENGYIN_E2E_BASE": "http://127.0.0.1:8099", "CHENGYIN_E2E_DB_HOST": "127.0.0.1", "CHENGYIN_E2E_DB_PORT": "3316",
             "CHENGYIN_E2E_DB_USER": "root", "CHENGYIN_E2E_DB_PASSWORD": "", "CHENGYIN_E2E_DB_SCHEMA": "cy_e2e_51"}.items():
    os.environ.setdefault(k, v)
for k in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"): os.environ.pop(k, None)
os.environ['PATH'] = RIG + '/work/bin:' + os.environ['PATH']
sys.path.insert(0, CAND + '/scripts/e2e')
import chain_lib as cl
RIGDIR = HOME + '/rig/docs/delivery/cloud-walk-20261101/rig'
STATE = HOME + '/l6-shots/state.json'
def login(openid):
    data = urllib.parse.urlencode({"code": "rig-" + openid}).encode()
    req = urllib.request.Request("http://127.0.0.1:8099/api/login/code", data=data, method="POST", headers={"content-type": "application/x-www-form-urlencoded"})
    r = json.loads(urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=20).read().decode())
    return r.get("token"), (r.get("data") or {})
def idcard(body17):
    w=[7,9,10,5,8,4,2,1,6,3,7,9,10,5,8,4,2]; c='10X98765432'
    return body17 + c[sum(int(a)*b for a,b in zip(body17,w))%11]
def J(x): return json.dumps(x, ensure_ascii=False, default=str)[:900]
def sh_now(): return datetime.datetime.utcnow() + datetime.timedelta(hours=8)
def fmt(d): return d.strftime('%Y-%m-%d %H:%M:%S')
def load():
    return json.load(open(STATE)) if os.path.exists(STATE) else {}
def save(s): json.dump(s, open(STATE, 'w'), ensure_ascii=False, indent=1)
def approve_all():
    atok = cl.admin_token()
    for tid_, bt, bid, ver in cl.q("SELECT id,biz_type,biz_id,version FROM audit_task WHERE status=0 ORDER BY id"):
        r = cl.http("POST", "/business/audit/review", token=atok, json_body={"taskId": tid_, "expectedVersion": ver, "status": 1, "reasonCode": "OK", "reasonText": "走查放行"})
        print('approve', tid_, bt, bid, (r.get('msg') or '')[:60])
