"""j5 rig helpers. Throwaway rig DB cy_e2e_51 on 127.0.0.1:3316 only. Fictional data only."""
import json, os, sys, pathlib
CAND = os.environ.get("CAND", os.path.expanduser("~/walk-rig/candidate"))
for k, v in {"CHENGYIN_E2E_BASE": "http://127.0.0.1:8099", "CHENGYIN_E2E_DB_HOST": "127.0.0.1", "CHENGYIN_E2E_DB_PORT": "3316",
             "CHENGYIN_E2E_DB_USER": "root", "CHENGYIN_E2E_DB_PASSWORD": "", "CHENGYIN_E2E_DB_SCHEMA": "cy_e2e_51"}.items():
    os.environ.setdefault(k, v)
for k in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"):
    os.environ.pop(k, None)
sys.path.insert(0, str(pathlib.Path(CAND) / "scripts" / "e2e"))
os.environ["PATH"] = os.path.expanduser("~/walk-rig/work/bin") + ":" + os.environ["PATH"]
from chain_lib import http, q, ex, admin_token, app_token  # noqa
MODE_FILE = "/tmp/rig-wxsec-mode"
WORD, ALLOW = "zqvxlm", "zqvxlmpark"   # made-up token / made-up harmless phrase
def wx_mode(m): open(MODE_FILE, "w").write(m)
def tok(openid):
    import urllib.request, urllib.parse
    req = urllib.request.Request("http://127.0.0.1:8099/api/login/code", data=urllib.parse.urlencode({"code": "rig-" + openid}).encode(), method="POST")
    o = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    return json.loads(o.open(req, timeout=20).read().decode())["token"]
