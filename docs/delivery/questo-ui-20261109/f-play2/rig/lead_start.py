"""Rig-only (qx-c5): player taps 我到了 and the club leader starts the trip, both via the real /api/club/lead/* endpoints."""
import json, os, sys, pathlib
for p in ("HTTP_PROXY","HTTPS_PROXY","http_proxy","https_proxy","ALL_PROXY","all_proxy"): os.environ.pop(p, None)
sys.path.insert(0, str(pathlib.Path(os.environ["CAND"]) / "scripts" / "e2e"))
from chain_lib import http, app_token, readback
act = int(sys.argv[1])
ptok, _ = app_token("13900000004"); ltok, _ = app_token("13900000003")
print("arrive", json.dumps(http("POST", "/api/club/lead/arrive", token=ptok, form={"activityId": act, "latitude": 31.230416, "longitude": 121.473701}), ensure_ascii=False)[:300])
print("start ", json.dumps(http("POST", "/api/club/lead/start", token=ltok, form={"activityId": act}), ensure_ascii=False)[:300])
readback("activity lead", "SELECT id,status,start_date FROM cms_activity WHERE id=%s", (act,))
