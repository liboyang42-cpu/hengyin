"""Step 6. 程一苇 (S1) cancels within 24 h of the meeting time: the real cancel-refund gives back 50%. The refund reconcile
job runs. FIXTURE TIME SHIFT (session day): both sessions start 1 minute ago (seed-walk shift-session semantics) so the
check-in and the group code can run now. Players check in at the gather point (real /api/club/lead/arrive with the
station coordinates). The lead shows the 团码 for each session; merchant 9002 (¥8/人) and 9005 (引流) each scan it."""
from common import *
st = state(); s1, s2 = st['sessions']; R = st['regs']
atok = admin(); lead, _ = login(st['lead_openid'])
def tok(o): return login(o)[0]
p4 = R['k5-p4']
if cl.q("SELECT payment_status FROM cms_registration WHERE id=%s", (p4['reg'],))[0][0] == 2:
    http('%s quote (refund terms now)' % p4['name'], 'POST', '/api/registration/quote', token=tok('k5-p4'), json_body={"ownerType": 2, "ownerId": s1, "ticketId": cl.q("SELECT ticket_id FROM cms_registration WHERE id=%s", (p4['reg'],))[0][0], "isUsePoint": 0})
    http('%s cancel-refund reg %s' % (p4['name'], p4['reg']), 'POST', '/api/registration/cancel-refund', token=tok('k5-p4'), form={"id": p4['reg']})
    run_job(atok, 'refundReconcileTask', 5)
q('refund_application', "SELECT id,source_id,paid_amount,refund_amount,status,payout_status FROM refund_application ORDER BY id")
for act in (s1, s2):
    before = cl.q("SELECT start_date,end_date FROM cms_activity WHERE id=%s", (act,))[0]
    ex("UPDATE cms_activity SET end_date=DATE_ADD(DATE_SUB(DATE_ADD(NOW(), INTERVAL 8 HOUR), INTERVAL 1 MINUTE), INTERVAL TIMESTAMPDIFF(SECOND,start_date,end_date) SECOND), start_date=DATE_SUB(DATE_ADD(NOW(), INTERVAL 8 HOUR), INTERVAL 1 MINUTE) WHERE id=%s", (act,))
    after = cl.q("SELECT start_date,end_date FROM cms_activity WHERE id=%s", (act,))[0]
    note('FIXTURE TIME SHIFT (session day) cms_activity %s: %s..%s -> %s..%s (Shanghai wall time; MySQL NOW() is UTC)' % (act, before[0], before[1], after[0], after[1]))
for o in ('k5-p1', 'k5-p2', 'k5-p3', 'k5-p5', 'k5-p6'):
    p = R[o]
    http('%s check-in /api/club/lead/arrive (activity %s)' % (p['name'], p['activity']), 'POST', '/api/club/lead/arrive', token=tok(o),
         form={"activityId": p['activity'], "latitude": "31.240018", "longitude": "121.490317"})
for act in (s1, s2):
    g = http('lead groupcode/issue activity %s' % act, 'POST', '/api/verify/groupcode/issue', token=lead, form={"activityId": act}, show=False)
    data = dict(g.get('data') or {}); data.pop('qrcodeUrl', None); log('lead groupcode/issue activity %s' % act, {"code": g.get('code'), "msg": g.get('msg'), "data": data})
    for m in (9002, 9005):
        http('merchant %d scans 团码 of activity %s' % (m, act), 'POST', '/api/verify/groupcode/redeem', token=tok('e2e-openid-%d' % m), form={"code": data.get('code')})
q('club_lead_checkin', "SELECT activity_id,member_id,arrived_at FROM club_lead_checkin ORDER BY id")
q('coop_group_verify', "SELECT id,topic_id,activity_id,coop_order_id,merchant_member_id,leader_member_id FROM coop_group_verify ORDER BY id")
money_table('after refund, check-ins and scans, before settlement', [s1, s2], [9001, 9002, 9005, st['lead']])
