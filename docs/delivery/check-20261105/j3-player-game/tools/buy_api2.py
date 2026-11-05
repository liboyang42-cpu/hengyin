"""API leg (labelled): consent + quote + create for a self-play pass, paid through the rig payment seam (real callback).
Usage: buy_api2.py <openid> <topicId> <name> <phone>"""
from common import *
import subprocess, time
openid, topic, name, phone = sys.argv[1:5]
tok, me = login(openid)
print('consent', J(cl.http("POST", "/api/compliance/consents", token=tok, json_body={"docType": "activity_host_data_sharing", "scene": "activity_signup", "eventType": "AGREE", "requestId": "j3c-%d" % time.time_ns()}))[:120])
q = cl.http("POST", "/api/registration/quote", token=tok, json_body={"ownerType": 1, "ownerId": topic, "isUsePoint": 0})
d = q.get('data') or {}
body = {"ownerType": 1, "ownerId": int(topic), "realName": name, "phone": phone, "isUsePoint": 0, "quoteSign": d.get('quoteSign'), "requestId": "j3-sp-%d" % time.time_ns()}
c = cl.http("POST", "/api/registration/create", token=tok, json_body=body)
print('create', J(c)[:300])
print(subprocess.run(['/root/walk-rig/venv/bin/python', '/root/rig/docs/delivery/cloud-walk-20261101/rig/pay.py', '--member', str(me['id'])], capture_output=True, text=True).stdout[-300:])
print(cl.q("SELECT id,owner_id,payable_amount,payment_status FROM cms_registration WHERE member_id=%s ORDER BY id DESC LIMIT 1", (me['id'],)))
