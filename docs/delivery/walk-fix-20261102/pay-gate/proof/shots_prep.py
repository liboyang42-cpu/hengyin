"""Round 3D screenshot prep (rig only): participant for 9009 via /api/user/address/action, a session starting within
24 h, and one paid, unrefunded order for the 付款成功 shot. Prints ids."""
import datetime, time
from rig_env import http, q, app_token, show, j, pay, CLUB_ID, OWNER_PHONE
ptok, _ = app_token("13900000009")
otok, _ = app_token(OWNER_PHONE)
show("address", j(http("POST", "/api/user/address/action", token=ptok, form={
    "fullName": "E2E 玩家二号", "mobilePhone": "13900000009", "isDefault": "1", "requestId": "r3d-addr-%d" % time.time()})))
now = datetime.datetime.utcnow() + datetime.timedelta(hours=8) + datetime.timedelta(hours=5)
now = now.replace(minute=(now.minute // 5) * 5, second=0, microsecond=0)
r = http("POST", "/api/club/open-session", token=otok, json_body={"clubId": CLUB_ID, "topicId": 990016,
         "sessionDate": now.strftime("%Y-%m-%d"), "sessionTime": now.strftime("%H:%M")})
show("open-session within 24h", j(r))
# a paid, kept order on activity 15 (>24 h) for the 付款成功 shot
act = 15
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
print(q("SELECT id,owner_id,payment_status,registration_status FROM cms_registration WHERE id=%s", (c["data"]["registrationId"],)))
