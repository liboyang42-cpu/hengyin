#!/usr/bin/env python3
"""Rig-only (f-play2): buy one session ticket over the same real endpoints the checkout page uses
(quote -> create), then pay through the rig pay seam (pay.py: verified fact + real /api/registration/callback).
Only for repeat runs after the browser purchase itself was walked. Usage: buy_http.py <activityId> <ticketId> <phone>"""
import json, os, pathlib, sys, subprocess, time
for p in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"): os.environ.pop(p, None)
sys.path.insert(0, str(pathlib.Path(os.environ["CAND"]) / "scripts" / "e2e"))
from chain_lib import http, app_token  # noqa: E402
act, ticket, phone = int(sys.argv[1]), int(sys.argv[2]), sys.argv[3]
tok, _ = app_token(phone)
body = {"ownerType": 2, "ownerId": act, "ticketId": ticket, "tickets": [{"ticketId": ticket, "count": 1}], "realName": "E2E 玩家", "phone": phone}
quote = http("POST", "/api/registration/quote", token=tok, json_body=body); body["quoteSign"] = quote["data"]["quoteSign"]
body["requestId"] = "rig-buy-%s-%s" % (act, time.strftime("%H%M%S"))
created = http("POST", "/api/registration/create", token=tok, json_body=body); print("create", json.dumps(created, ensure_ascii=False)[:160])
rno = created["data"]["registrationNo"]
print(subprocess.run([sys.executable, os.environ["RIG"] + "/pay.py", "--reg-no", rno], capture_output=True, text=True).stdout.strip()[:240])
print("REG_ID=%s" % created["data"]["registrationId"])
