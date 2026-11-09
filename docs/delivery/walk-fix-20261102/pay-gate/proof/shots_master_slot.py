"""Before-shots on master only: master refuses payment for club-owned themes (assertCollectible), so for a comparable
before state set theme <id>'s CONTENT slot to PLATFORM via the real admin endpoint, then buy+pay one order on <act>."""
import sys, time
from rig_env import http, q, admin_token, app_token, show, j, pay, readback
tid, act = int(sys.argv[1]), int(sys.argv[2])
show("content-slot", j(http("PUT", "/business/topic/%d/content-slot" % tid, token=admin_token(), json_body={"entityType": "PLATFORM"})))
readback("slot", "SELECT slot,entity_type,entity_id FROM product_role WHERE topic_id=%s AND status=1", (tid,))
ptok, _ = app_token("13900000009")
http("POST", "/api/compliance/consents", token=ptok, json_body={"docType": "activity_host_data_sharing",
     "scene": "activity_signup", "eventType": "AGREE", "requestId": "r3d-consent-m-%s" % act})
t = q("SELECT id FROM oms_ticket WHERE owner_type=2 AND owner_id=%s AND del_flag=0 ORDER BY id LIMIT 1", (act,))[0][0]
b = {"ownerType": 2, "ownerId": act, "ticketId": t, "tickets": [{"ticketId": t, "count": 1}], "realName": "E2E 玩家二号", "phone": "13900000009"}
qr = http("POST", "/api/registration/quote", token=ptok, json_body=b); show("quote", j(qr, 300))
b["quoteSign"] = qr["data"]["quoteSign"]; b["requestId"] = "r3d-m-%d" % time.time()
c = http("POST", "/api/registration/create", token=ptok, json_body=b); show("create", j(c, 150))
pay(c["data"]["registrationNo"], c["data"]["registrationId"]); print("REG=%s" % c["data"]["registrationId"])
