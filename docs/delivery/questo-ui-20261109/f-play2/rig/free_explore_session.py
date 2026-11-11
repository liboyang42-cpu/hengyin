#!/usr/bin/env python3
"""Rig-only fixture (f-play2 path 2): after f-money2's refreshed `chains.py sale_close` has put the free-explore
topic in SELLING (real sale snapshot, gate untouched), prepare ONE session for a real browser registration.
Same real entries as scripts/e2e/chain_player.py, minus the HTTP purchase (the player buys in the mini program):
  1. admin   PUT /business/topic/{id}/content-slot PLATFORM   (二清闸: only PLATFORM-slot sessions collect money)
  2. club    POST /api/club/open-session (content-owner club 7001, JVM-today, now + LEAD minutes)
  3. player  POST /api/compliance/consents, /api/user/address/action, /api/club/join  (checkout prerequisites)
Usage: CAND=... $RIG_HOME/venv/bin/python free_explore_session.py <topicId> [player_phone]   -> ACTIVITY_ID / TICKET_ID
"""
import json, os, pathlib, sys, time
for p in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy", "ALL_PROXY", "all_proxy"):
    os.environ.pop(p, None)
sys.path.insert(0, str(pathlib.Path(os.environ["CAND"]) / "scripts" / "e2e"))
from chain_lib import http, q, admin_token, app_token, step, show, readback  # noqa: E402

tid = int(sys.argv[1]); phone = sys.argv[2] if len(sys.argv) > 2 else "13900000004"
lead = int(os.environ.get("LEAD", "5")); stamp = time.strftime("%m%d%H%M%S")
atok = admin_token(); ctok, _ = app_token("13900000003"); ptok, _ = app_token(phone)
step("1 内容槽 PLATFORM（同 chain_player）")
show("content-slot", (http("PUT", "/business/topic/%d/content-slot" % tid, token=atok, json_body={"entityType": "PLATFORM"}).get("msg") or "")[:30])
step("2 俱乐部 7001 开今天这一场")
day, hm = q("SELECT DATE_FORMAT(DATE_ADD(NOW(), INTERVAL 8 HOUR) + INTERVAL %s MINUTE, '%%Y-%%m-%%d'), "
            "DATE_FORMAT(DATE_ADD(NOW(), INTERVAL 8 HOUR) + INTERVAL %s MINUTE, '%%H:%%i')", (lead, lead))[0]
r = http("POST", "/api/club/open-session", token=ctok, json_body={"clubId": 7001, "topicId": tid, "sessionDate": day, "sessionTime": hm})
show("open-session %s %s" % (day, hm), json.dumps(r, ensure_ascii=False)[:160])
act = (r.get("data") or {}).get("activityId")
if not act:
    sys.exit(1)
tickets = readback("场次票", "SELECT id,price,signup_deadline FROM oms_ticket WHERE owner_type=2 AND owner_id=%s ORDER BY id", (act,))
step("3 玩家结算前置：同意 / 参与人 / 入俱乐部")
show("consents", (http("POST", "/api/compliance/consents", token=ptok, json_body={"docType": "activity_host_data_sharing",
     "scene": "activity_signup", "eventType": "AGREE", "requestId": "rig-fe-consent-%s" % stamp}).get("msg") or "")[:20])
show("address", (http("POST", "/api/user/address/action", token=ptok, form={"fullName": "E2E 玩家", "mobilePhone": phone,
     "isDefault": "1", "requestId": "rig-fe-addr-%s" % stamp}).get("msg") or "")[:20])
show("club/join", json.dumps(http("POST", "/api/club/join", token=ptok, json_body={"id": 7001, "joinMessage": "rig"}), ensure_ascii=False)[:120])
print("\nACTIVITY_ID=%s\nTICKET_ID=%s" % (act, tickets[0][0]))
