"""K (API leg, labelled): self-play pass bought through /api/registration/quote + /create (ownerType=1) by member j2-m3,
paid through the rig payment seam (real callback). The UI path stops at the profile page's WeChat phone binding (HARNESS)."""
from common import *
import subprocess
tok, me = login('j2-m3')
q = cl.http("POST", "/api/registration/quote", token=tok, json_body={"ownerType": 1, "ownerId": "990014", "isUsePoint": 0})
print('quote', J(q)[:400])
d = q.get('data') or {}
body = {"ownerType": 1, "ownerId": "990014", "realName": "陈小满", "phone": "13800003333", "email": "", "isUsePoint": 0,
        "quoteSign": d.get('quoteSign') or d.get('sign'), "hostShareConsent": True, "requestId": "j2-selfplay-%d" % int(datetime.datetime.now().timestamp())}
for k in ('ticketId',):
    if d.get(k): body[k] = d[k]
c = cl.http("POST", "/api/registration/create", token=tok, json_body=body)
print('create', J(c)[:400])
print(subprocess.run(['/root/walk-rig/venv/bin/python', '/root/rig/docs/delivery/cloud-walk-20261101/rig/pay.py', '--member', str(me['id'])], capture_output=True, text=True).stdout)
print(cl.q("SELECT id,owner_type,owner_id,payable_amount,payment_status FROM cms_registration WHERE member_id=%s ORDER BY id DESC LIMIT 1", (me['id'],)))
