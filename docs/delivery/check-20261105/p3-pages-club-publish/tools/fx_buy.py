"""FIXTURE (API, labelled): players buy a ticket for club session activity 15, the way chain_player.py does:
consent -> quote -> create through the app's own endpoints, then the rig's payment seam (pay.py: a VERIFIED raw
payment fact + the real POST /api/registration/callback). payment_status is never written directly."""
import sys, json, importlib.util, os
from fx_common import *
spec = importlib.util.spec_from_file_location('pay', os.path.expanduser('~/rig/docs/delivery/cloud-walk-20261101/rig/pay.py'))
pay = importlib.util.module_from_spec(spec); spec.loader.exec_module(pay)
ACT = int(sys.argv[1])
NAMES = {"e2e-openid-9004": ("林一帆", "13800000101"), "p3-pa": ("周小雨", "13800000102"), "p3-pb": ("陈小满", "13800000103")}
ticket = cl.q("SELECT id FROM oms_ticket WHERE owner_type=2 AND owner_id=%s ORDER BY id LIMIT 1", (ACT,))[0][0]
for oid in sys.argv[2:]:
    tok, me = login(oid); name, phone = NAMES[oid]
    cl.http("POST", "/api/compliance/consents", token=tok, json_body={"docType": "activity_host_data_sharing", "scene": "activity_signup", "eventType": "AGREE", "requestId": "p3-consent-%s" % oid})
    body = {"ownerType": 2, "ownerId": ACT, "ticketId": ticket, "tickets": [{"ticketId": ticket, "count": 1}], "realName": name, "phone": phone}
    qt = cl.http("POST", "/api/registration/quote", token=tok, json_body=body)
    print(oid, 'quote', J(qt.get('data') or qt)[:200])
    if not qt.get('data'): continue
    body["quoteSign"] = qt["data"]["quoteSign"]; body["requestId"] = "p3-reg-%s-%d" % (oid, ACT)
    cr = cl.http("POST", "/api/registration/create", token=tok, json_body=body)
    print(oid, 'create', J(cr)[:200])
    if not cr.get('data'): continue
    print(oid, 'pay', J(pay.pay(member=me['id'], reg_no=cr['data']['registrationNo']))[:300])
