"""Step 5. Players (fictional, throwaway openids) join the club, give the 报名信息单独同意, quote and buy a ¥39 ticket with the
app's body; payment goes through the rig pay seam (verified fact + the real /api/registration/callback; payment_status is
never written). S1 (activity 9): 4 players. S2 (activity 10): 2 players."""
from common import *
sys.path.insert(0, os.path.expanduser('~/rigsrc/docs/delivery/cloud-walk-20261101/rig'))
import pay as rigpay
club = state()['club']; s1, s2 = state()['sessions']
P = [("k5-p1", "顾清和", s1), ("k5-p2", "许知微", s1), ("k5-p3", "陆星野", s1), ("k5-p4", "程一苇", s1),
     ("k5-p5", "周晚晴", s2), ("k5-p6", "唐不言", s2)]
regs = state().get('regs', {})
for openid, name, act in P:
    if openid in regs: continue
    tok, me = login(openid); mid = me.get('id')
    ticket = cl.q("SELECT id FROM oms_ticket WHERE owner_type=2 AND owner_id=%s", (act,))[0][0]
    http('%s (%s) club/join' % (name, mid), 'POST', '/api/club/join', token=tok, json_body={"id": club})
    http('%s 报名信息单独同意' % name, 'POST', '/api/compliance/consents', token=tok, json_body={
        "docType": "activity_host_data_sharing", "scene": "activity_signup", "eventType": "AGREE", "docVersion": "v2.1",
        "requestId": "k5-consent-%s" % mid})
    qt = http('%s quote' % name, 'POST', '/api/registration/quote', token=tok, json_body={"ownerType": 2, "ownerId": act, "ticketId": ticket, "isUsePoint": 0})
    sign = (qt.get('data') or {}).get('quoteSign')
    r = http('%s registration/create' % name, 'POST', '/api/registration/create', token=tok, json_body={
        "ownerType": 2, "ownerId": act, "ticketId": ticket, "realName": name, "phone": "1380000%04d" % (mid % 10000), "email": "",
        "isUsePoint": 0, "quoteSign": sign, "requestId": "k5-buy-%s" % mid})
    rid = (r.get('data') or {}).get('registrationId')
    paid = rigpay.pay(reg_no=(r.get('data') or {}).get('registrationNo'))
    log('%s pay seam (verified fact + real callback)' % name, paid)
    regs[openid] = {"member": mid, "reg": rid, "activity": act, "name": name}; save(regs=regs)
q('paid registrations', "SELECT id,owner_id,member_id,payable_amount,payment_status,registration_status,verification_status,fee_platform_rate FROM cms_registration WHERE owner_type=2 AND owner_id IN (%s,%s) ORDER BY id" % (s1, s2))
q('settlement_detail at payment', "SELECT id,source_id,receiver_type,receiver_id,gross_amount,settlement_amount,status FROM settlement_detail ORDER BY id")
