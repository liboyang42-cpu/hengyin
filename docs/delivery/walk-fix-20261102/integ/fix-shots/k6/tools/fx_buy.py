"""FIXTURE (API leg, labelled): a player buys one 自由探索 ticket for a club session of 990014 through the real
quote/create endpoints and the real payment callback (rig payment seam = chain_player.store_trusted_payment_fact).
Usage: fx_buy.py <openid> <activityId> <realName>"""
import os, sys, json, urllib.request, urllib.parse
RIG = '/root/walk-rig'
sys.path.insert(0, RIG + '/candidate/scripts/e2e')
for k in ("HTTP_PROXY","HTTPS_PROXY","http_proxy","https_proxy"): os.environ.pop(k, None)
os.environ['PATH'] = RIG + '/work/bin:' + os.environ['PATH']
import chain_lib as cl
from chain_player import store_trusted_payment_fact
def login(openid):
    data = urllib.parse.urlencode({"code": "rig-" + openid}).encode()
    req = urllib.request.Request("http://127.0.0.1:8099/api/login/code", data=data, method="POST")
    r = json.loads(urllib.request.build_opener(urllib.request.ProxyHandler({})).open(req, timeout=20).read().decode())
    return r.get("token"), (r.get("data") or {})
openid, act, name = sys.argv[1], int(sys.argv[2]), sys.argv[3]
tok, me = login(openid); print('member', me.get('id'))
print('consent', cl.http("POST", "/api/compliance/consents", token=tok, json_body={"docType": "activity_host_data_sharing", "scene": "activity_signup", "eventType": "AGREE", "requestId": "p2-consent-%s-%d" % (openid, act)}).get('msg'))
ticket = cl.q("SELECT id FROM oms_ticket WHERE owner_type=2 AND owner_id=%s ORDER BY id LIMIT 1", (act,))[0][0]
body = {"ownerType": 2, "ownerId": act, "ticketId": ticket, "tickets": [{"ticketId": ticket, "count": 1}], "realName": name, "phone": "1380000%04d" % (me.get('id') % 10000)}
quote = cl.http("POST", "/api/registration/quote", token=tok, json_body=body); print('quote', json.dumps(quote, ensure_ascii=False)[:300])
if not quote.get('data'): sys.exit(1)
body["quoteSign"] = quote["data"]["quoteSign"]; body["requestId"] = "p2-reg-%s-%d" % (openid, act)
created = cl.http("POST", "/api/registration/create", token=tok, json_body=body); print('create', json.dumps(created, ensure_ascii=False)[:300])
reg, reg_no = created["data"]["registrationId"], created["data"]["registrationNo"]
fen = int(round(float(quote["data"].get("payAmount") or quote["data"].get("totalAmount")) * 100))
fact = store_trusted_payment_fact(reg_no, "P2-TXN-%d" % reg, fen)
cb = cl.http("POST", "/api/registration/callback", json_body=dict(fact, **{"out_trade_no": reg_no, "transaction_id": "P2-TXN-%d" % reg, "trade_state": "SUCCESS", "total": fen}))
print('callback', json.dumps(cb, ensure_ascii=False)[:200])
print(cl.q("SELECT id,member_id,payment_status,payable_amount FROM cms_registration WHERE id=%s", (reg,)))
