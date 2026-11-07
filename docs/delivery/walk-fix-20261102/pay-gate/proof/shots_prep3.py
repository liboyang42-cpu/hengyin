"""Round 3D screenshot prep 3 (rig only). Theme 990017 (club 7002, owner 9004): fixture lifecycle -> on sale (the sale
review is blocked by SALE_ORGANIZER_ONLY_FREE_EXPLORE), a session in 3 days and one starting within 24 h, and a paid
order on the first for the 付款成功 shot. Prints ids."""
import datetime, sys, time
from rig_env import http, q, app_token, show, j, pay
import chain_lib
TOPIC = int(sys.argv[1]) if len(sys.argv) > 1 else 990017
OWNER = sys.argv[2] if len(sys.argv) > 2 else "13900000004"
n = chain_lib.ex("UPDATE cms_topic SET lifecycle=3, selling_started_at=NOW() WHERE id=%s AND lifecycle=2", (TOPIC,))
show("FIXTURE lifecycle 2->3 rows", n)
otok, _ = app_token(OWNER)
ptok, _ = app_token("13900000009")
cst = datetime.datetime.utcnow() + datetime.timedelta(hours=8)
ids = {}
for key, at in (("later", (cst + datetime.timedelta(days=3)).replace(hour=10, minute=0, second=0, microsecond=0)),
                ("within24h", (cst + datetime.timedelta(hours=5)).replace(second=0, microsecond=0))):
    at = at.replace(minute=(at.minute // 5) * 5)
    r = http("POST", "/api/club/open-session", token=otok, json_body={"clubId": 7002, "topicId": TOPIC,
             "sessionDate": at.strftime("%Y-%m-%d"), "sessionTime": at.strftime("%H:%M")})
    show("open-session %s %s" % (key, at), j(r))
    ids[key] = (r.get("data") or {}).get("activityId")
act = ids["later"]
http("POST", "/api/compliance/consents", token=ptok, json_body={"docType": "activity_host_data_sharing",
     "scene": "activity_signup", "eventType": "AGREE", "requestId": "r3d-consent-shot-%s" % act})
t = q("SELECT id FROM oms_ticket WHERE owner_type=2 AND owner_id=%s AND del_flag=0 ORDER BY id LIMIT 1", (act,))[0][0]
body = {"ownerType": 2, "ownerId": act, "ticketId": t, "tickets": [{"ticketId": t, "count": 1}],
        "realName": "E2E 玩家二号", "phone": "13900000009"}
body["quoteSign"] = http("POST", "/api/registration/quote", token=ptok, json_body=body)["data"]["quoteSign"]
body["requestId"] = "r3d-shot-%d" % time.time()
c = http("POST", "/api/registration/create", token=ptok, json_body=body)
show("create", j(c))
pay(c["data"]["registrationNo"], c["data"]["registrationId"])
ids["paidOrder"] = c["data"]["registrationId"]
print("IDS", ids, q("SELECT id,payment_status,registration_status FROM cms_registration WHERE id=%s", (ids["paidOrder"],)))
