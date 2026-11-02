import sys; sys.path.insert(0, '/root/sc')
from common import *
openid, act = sys.argv[1], int(sys.argv[2])
tok, me = login(openid)
print('member', me.get('id'))
print('consent', J(cl.http("POST", "/api/compliance/consents", token=tok, json_body={"docType": "activity_host_data_sharing", "scene": "activity_signup", "eventType": "AGREE", "requestId": "walk-consent-%s-%d" % (openid, act)}))[:120])
t = cl.q("SELECT id,price,total_inventory FROM oms_ticket WHERE owner_type=2 AND owner_id=%s AND del_flag=0 ORDER BY id", (act,))
print('tickets', t)
if not t: sys.exit()
body = {"ownerType": 2, "ownerId": act, "ticketId": t[0][0], "tickets": [{"ticketId": t[0][0], "count": 1}], "realName": sys.argv[3] if len(sys.argv)>3 else "走查玩家", "phone": sys.argv[4] if len(sys.argv)>4 else "13700000001"}
q = cl.http("POST", "/api/registration/quote", token=tok, json_body=body); print('quote', J(q))
