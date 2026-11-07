#!/usr/bin/env python3
"""C-22 and P-07 over real HTTP in the walkthrough rig, after `chains.py player` (theme 990014, a 探店日
edition sold by club 7002; player 9009 holds a paid ticket on the chain's session).
C-22: 9009 tries another session of the same edition -> plain sentence (edition stays one-per-member).
P-07: a session moved to start in 60 min (FIXTURE time shift, same as seed-walk.py shift-session); player 9004
quotes, signs up, pays through the rig seam (real /api/registration/callback), reads the order, cancels.
The quote's refundDeadline must equal the order's refundInfo.deadline and the DB refund_deadline."""
import os, sys, json, time, subprocess, urllib.request, urllib.parse
RIG = os.environ.get("RIG_HOME", os.path.expanduser("~/walk-rig"))
sys.path.insert(0, RIG + "/candidate/scripts/e2e")
for k in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"):
    os.environ.pop(k, None)
os.environ["PATH"] = RIG + "/work/bin:" + os.environ["PATH"]
from chain_lib import http, q, readback  # noqa: E402
RIGDIR = os.path.expanduser("~/rig/docs/delivery/cloud-walk-20261101/rig")
J = lambda x: json.dumps(x, ensure_ascii=False)[:500]


def login(openid):
    d = urllib.parse.urlencode({"code": "rig-" + openid}).encode()
    r = urllib.request.build_opener(urllib.request.ProxyHandler({})).open(
        urllib.request.Request("http://127.0.0.1:8099/api/login/code", data=d), timeout=20)
    return json.loads(r.read())["token"]


def consent(tok):
    http("POST", "/api/compliance/consents", token=tok, json_body={
        "docType": "activity_host_data_sharing", "scene": "activity_signup", "eventType": "AGREE",
        "requestId": "rig-consent-%d" % int(time.time() * 1000)})


TOPIC = int(sys.argv[1]) if len(sys.argv) > 1 else 990014
sessions = q("SELECT a.id,t.id FROM cms_activity a JOIN oms_ticket t ON t.owner_type=2 AND t.owner_id=a.id "
             "AND t.del_flag=0 WHERE a.topic_id=%s ORDER BY a.id", (TOPIC,))
held = q("SELECT owner_id FROM cms_registration WHERE member_id=9009 AND owner_type=2 AND payment_status=2 "
         "AND owner_id IN (SELECT id FROM cms_activity WHERE topic_id=%s)", (TOPIC,))
readback("edition rule", "SELECT JSON_EXTRACT(canonical_json,'$.completionPolicy.evidenceRule') FROM topic_sale_snapshot WHERE topic_id=%s", (TOPIC,))
print("sessions", sessions, "9009 holds a paid ticket on", held)
other = [s for s in sessions if s[0] not in [h[0] for h in held]][0]

print("\n== C-22: 9009 signs up for another session of the same edition")
p = login("e2e-openid-9009")
consent(p)
qr = http("POST", "/api/registration/quote", token=p, json_body={"ownerType": 2, "ownerId": other[0], "ticketId": other[1], "isUsePoint": 0})
cr = http("POST", "/api/registration/create", token=p, json_body={
    "ownerType": 2, "ownerId": other[0], "ticketId": other[1], "isUsePoint": 0, "realName": "许知远", "phone": "13800000789",
    "quoteSign": (qr.get("data") or {}).get("quoteSign"), "requestId": "rig-c22-%d" % int(time.time())})
print("create", J(cr))

print("\n== P-07: session %d moved to start in 60 min (fixture)" % other[0])
print(subprocess.run([sys.executable, RIGDIR + "/seed-walk.py", "shift-session", str(other[0]), "--start-in-min", "60"],
                     capture_output=True, text=True).stdout.strip().splitlines()[0])
b = login("e2e-openid-9004")
print("join club 7002", J(http("POST", "/api/club/join", token=b, json_body={"id": 7002, "joinMessage": ""})))
consent(b)
qr = http("POST", "/api/registration/quote", token=b, json_body={"ownerType": 2, "ownerId": other[0], "ticketId": other[1], "isUsePoint": 0})
print("quote", J(qr))
cr = http("POST", "/api/registration/create", token=b, json_body={
    "ownerType": 2, "ownerId": other[0], "ticketId": other[1], "isUsePoint": 0, "realName": "林小舟", "phone": "13800000444",
    "quoteSign": (qr.get("data") or {}).get("quoteSign"), "requestId": "rig-p07-%d" % int(time.time())})
print("create", J(cr))
rid = (cr.get("data") or {}).get("registrationId")
print("pay", subprocess.run([sys.executable, RIGDIR + "/pay.py", "--member", "9004"], capture_output=True, text=True).stdout.strip())
info = (http("POST", "/api/registration/info", token=b, form={"id": rid}).get("data") or {})
print("order", J({k: info.get(k) for k in ("paymentStatus", "refundDeadlineDisplay", "refundInfo", "refundPolicyCode")}))
readback("registration", "SELECT id,payment_status,refund_policy_code,refund_deadline FROM cms_registration WHERE id=%s", (rid,))
print("SAME DEADLINE (quote == order == db):",
      (qr.get("data") or {}).get("refundDeadline") == (info.get("refundInfo") or {}).get("deadline"))
print("cancel-refund", J(http("POST", "/api/registration/cancel-refund", token=b, form={"id": rid})))
readback("after cancel", "SELECT id,payment_status,registration_status FROM cms_registration WHERE id=%s", (rid,))
print("REG_ID", rid)
