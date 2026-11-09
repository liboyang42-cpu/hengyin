"""Round 3D screenshot step: player 9009 buys and pays one session ticket on activity <id> (real quote/create, rig
payment seam + real callback). Prints the registration id."""
import sys, time
from rig_env import http, q, app_token, show, j, pay
act = int(sys.argv[1])
ptok, _ = app_token("13900000009")
http("POST", "/api/compliance/consents", token=ptok, json_body={"docType": "activity_host_data_sharing",
     "scene": "activity_signup", "eventType": "AGREE", "requestId": "r3d-consent-buy-%s" % act})
t = q("SELECT id FROM oms_ticket WHERE owner_type=2 AND owner_id=%s AND del_flag=0 ORDER BY id LIMIT 1", (act,))[0][0]
b = {"ownerType": 2, "ownerId": act, "ticketId": t, "tickets": [{"ticketId": t, "count": 1}],
     "realName": "E2E 玩家二号", "phone": "13900000009"}
b["quoteSign"] = http("POST", "/api/registration/quote", token=ptok, json_body=b)["data"]["quoteSign"]
b["requestId"] = "r3d-buy-%d" % time.time()
c = http("POST", "/api/registration/create", token=ptok, json_body=b)
show("create", j(c, 200))
pay(c["data"]["registrationNo"], c["data"]["registrationId"])
print("REG=%s" % c["data"]["registrationId"])
