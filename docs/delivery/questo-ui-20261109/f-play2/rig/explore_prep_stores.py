#!/usr/bin/env python3
"""Rig-only (f-play2 path 2b): bring a paid free-explore registration to 3/4 through the same real endpoints the
browser uses (player /api/play/checkin with the store code, /api/verify/dyncode/issue; merchant
/api/registration/scan_qr_code), so the LAST store can be walked in the browser with the entry code on screen.
Usage: explore_prep_stores.py <topicId> <activityId> <registrationId> <playerPhone> [stores=3]"""
import json, os, pathlib, sys
for p in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"): os.environ.pop(p, None)
sys.path.insert(0, str(pathlib.Path(os.environ["CAND"]) / "scripts" / "e2e"))
from chain_lib import http, q, app_token  # noqa: E402
tid, act, reg, phone = int(sys.argv[1]), int(sys.argv[2]), int(sys.argv[3]), sys.argv[4]
n = int(sys.argv[5]) if len(sys.argv) > 5 else 3
MERCH = {9002: "13900000002", 9005: "13900000005", 9006: "13900000006", 9007: "13900000007"}
ptok, _ = app_token(phone)
for nid, ch, code, merchant in q("SELECT id,chapter_id,checkin_code,merchant_member_id FROM cms_topic_node WHERE topic_id=%s "
                                 "AND merchant_member_id IS NOT NULL ORDER BY chapter_id LIMIT %s", (tid, n)):
    print("checkin ch%s" % ch, http("POST", "/api/play/checkin", token=ptok, form={"activityId": act, "code": code, "topicId": tid}).get("msg"))
    dyn = http("POST", "/api/verify/dyncode/issue", token=ptok, form={"registrationId": reg, "type": "activity"})
    mtok, _ = app_token(MERCH[merchant])
    r = http("POST", "/api/registration/scan_qr_code", token=mtok, form={"type": "activity", "code": dyn["data"]["code"]})
    print("verify ch%s" % ch, json.dumps(r, ensure_ascii=False)[:120])
print(q("SELECT chapter_id,status FROM registration_entitlement WHERE registration_id=%s ORDER BY chapter_id", (reg,)))
