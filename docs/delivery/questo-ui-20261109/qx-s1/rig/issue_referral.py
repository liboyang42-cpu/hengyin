"""Rig-only (qx-s1): player 9004 issues a referral token for topic 990014 through the real share endpoint the report page calls."""
import json, os, sys, pathlib
for p in ("HTTP_PROXY","HTTPS_PROXY","http_proxy","https_proxy","ALL_PROXY","all_proxy"): os.environ.pop(p, None)
sys.path.insert(0, str(pathlib.Path(os.environ["CAND"]) / "scripts" / "e2e"))
from chain_lib import http, app_token
ptok, _ = app_token("13900000004")
r = http("POST", "/api/marketing/referral/token", token=ptok, json_body={"targetType": 1, "targetId": int(sys.argv[1])})
print(json.dumps(r, ensure_ascii=False)[:400])
