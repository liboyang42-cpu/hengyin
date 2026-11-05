#!/usr/bin/env python3
"""p1 second pass fixture helper. Run with $RIG_HOME/venv/bin/python.
  fx.py call <openid|admin> <METHOD> <path> [json]     real HTTP as that user; prints body
  fx.py sql "<select ...>"                               read-back
  fx.py exec "<sql>"                                     FIXTURE write to the throwaway rig DB (reported as such)
"""
import json, os, sys, urllib.request, urllib.parse
from pathlib import Path
RIG_HOME = Path(os.environ.get("RIG_HOME", str(Path.home() / "walk-rig")))
CAND = RIG_HOME / "candidate"
for k, v in {"CHENGYIN_E2E_BASE": "http://127.0.0.1:8099", "CHENGYIN_E2E_DB_HOST": "127.0.0.1", "CHENGYIN_E2E_DB_PORT": "3316",
             "CHENGYIN_E2E_DB_USER": "root", "CHENGYIN_E2E_DB_PASSWORD": "", "CHENGYIN_E2E_DB_SCHEMA": "cy_e2e_51"}.items():
    os.environ.setdefault(k, v)
for k in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"):
    os.environ.pop(k, None)
sys.path.insert(0, str(CAND / "scripts" / "e2e"))
import chain_lib as cl  # noqa
_tok = {}
def token(who):
    if who in _tok: return _tok[who]
    if who == "admin":
        t = cl.admin_token()
    else:
        data = urllib.parse.urlencode({"code": "rig-" + who}).encode()
        req = urllib.request.Request("http://127.0.0.1:8099/api/login/code", data=data, method="POST",
                                     headers={"content-type": "application/x-www-form-urlencoded"})
        t = json.loads(urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=20).read().decode())["token"]
    _tok[who] = t; return t
def call(who, method, path, body=None, form=None):
    t = None if who == "guest" else token(who)
    return cl.http(method, path, token=t, json_body=body, form=form)
if __name__ == "__main__":
    a = sys.argv[1:]
    if a[0] == "call":
        body = json.loads(a[4]) if len(a) > 4 else None
        r = call(a[1], a[2], a[3], body if a[2] != "GET" else None)
        print(json.dumps(r, ensure_ascii=False, default=str)[:6000])
    elif a[0] == "form":
        f = dict(x.split("=", 1) for x in a[3:])
        print(json.dumps(call(a[1], "POST", a[2], form=f), ensure_ascii=False, default=str)[:6000])
    elif a[0] == "sql":
        for r in cl.q(a[1].replace("%", "%%")): print(r)
    elif a[0] == "exec":
        print(cl.ex(a[1].replace("%", "%%")))
