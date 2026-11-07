"""Step 7. The 14-day wait and the real settlement job (分润结算（活动+主题）), then a re-run, a refund after settlement
(organiser path /api/registration/cancel-by-owner; a player refund after the start is refused by policy), another re-run,
and the theme settlement. TIME SHIFTs are labelled. Read-backs after every step; the screens' own APIs are read too."""
from common import *
st = state(); s1, s2 = st['sessions']; R = st['regs']; tid = st['topic']; LEAD = st['lead']
atok = admin(); lead, _ = login(st['lead_openid']); m1, _ = login('e2e-openid-9002'); m2, _ = login('e2e-openid-9005')
MEM = [9001, 9002, 9005, LEAD]
def screens(label):
    s = http('俱乐部结算 /api/club/settlement/summary (lead) [%s]' % label, 'POST', '/api/club/settlement/summary', token=lead, json_body={"clubId": st['club']}, show=False)
    log('俱乐部结算 sessions [%s]' % label, (s.get('data') or {}).get('sessions'))
    for name, t in (('9002', m1), ('9005', m2)):
        r = http('merchant %s 核销与退款 [%s]' % (name, label), 'POST', '/api/merchant/finance/redemptions', token=t, json_body={"filter": "all", "pageNum": 1, "pageSize": 20}, show=False)
        d = r.get('data') or {}
        log('merchant %s 核销与退款 summary/rows [%s]' % (name, label), {"summary": d.get('summary'), "rows": [{k: x.get(k) for k in ('recordType', 'recordId', 'topicName', 'chapterName', 'settlementAmount', 'displayState', 'destination', 'unitFee', 'headCount', 'refundState')} for x in (d.get('rows') or [])]})
        e = http('merchant %s 财务 settlement-entries [%s]' % (name, label), 'POST', '/api/merchant/finance/settlement-entries', token=t, json_body={"source": "all", "pageNum": 1, "pageSize": 20}, show=False)
        log('merchant %s 财务 entries [%s]' % (name, label), [{k: x.get(k) for k in ('source', 'entryKind', 'signedAmount', 'destination', 'displayState', 'sourceId')} for x in ((e.get('data') or {}).get('rows') or [])])
    for name, t in (('lead', lead), ('9002', m1)):
        w = http('wallet/stages %s [%s]' % (name, label), 'POST', '/api/wallet/stages', token=t, json_body={}, show=False)
        log('wallet/stages %s [%s]' % (name, label), w.get('data'))
def shift_end(days):
    for act in (s1, s2):
        ex("UPDATE cms_activity SET start_date=DATE_SUB(DATE_ADD(NOW(), INTERVAL 8 HOUR), INTERVAL %s DAY) - INTERVAL 8 HOUR, end_date=DATE_SUB(DATE_ADD(NOW(), INTERVAL 8 HOUR), INTERVAL %s DAY) WHERE id=%s", (days, days, act))
    note('TIME SHIFT (settlement wait): sessions %s,%s now ended %d days ago (Shanghai wall time)' % (s1, s2, days))
    q('sessions', "SELECT id,start_date,end_date,is_settlement FROM cms_activity WHERE id IN (%s,%s)" % (s1, s2))
step = sys.argv[1] if len(sys.argv) > 1 else 'all'
if step in ('all', 'wait'):
    shift_end(13)
    screens('13 days after the end, before the job')
    run_job(atok, 'settlementTask', 8)
    money_table('13 days: job ran, nothing due', [s1, s2], MEM)
if step in ('all', 'settle'):
    shift_end(15)
    run_job(atok, 'settlementTask', 8)
    money_table('15 days: settlement job ran', [s1, s2], MEM)
    screens('after settlement')
    run_job(atok, 'settlementTask', 8)
    money_table('re-run of the settlement job', [s1, s2], MEM)
if step in ('all', 'refund'):
    p3 = R['k5-p3']
    http('%s cancel-refund after settlement (player)' % p3['name'], 'POST', '/api/registration/cancel-refund', token=login('k5-p3')[0], form={"id": p3['reg']})
    http('lead cancel-by-owner reg %s (after settlement)' % p3['reg'], 'POST', '/api/registration/cancel-by-owner', token=lead, form={"id": p3['reg']})
    run_job(atok, 'refundReconcileTask', 5)
    money_table('refund of reg %s after settlement' % p3['reg'], [s1, s2], MEM)
    q('debts', "SELECT member_id,source_type,source_id,amount,status FROM ums_member_settlement_debt ORDER BY id")
    screens('after the refund after settlement')
    run_job(atok, 'settlementTask', 8)
    money_table('re-run after the refund', [s1, s2], MEM)
if step in ('all', 'theme'):
    q('topic before', "SELECT id,is_settlement,end_date,lifecycle FROM cms_topic WHERE id=%s", (tid,))
    note('TIME SHIFT (theme end): cms_topic %s end_date -> 15 days ago so the theme settlement picks it up' % tid)
    ex("UPDATE cms_topic SET end_date=DATE_SUB(NOW(), INTERVAL 15 DAY) WHERE id=%s", (tid,))
    run_job(atok, 'settlementTask', 8)
    q('topic after', "SELECT id,is_settlement,end_date,lifecycle FROM cms_topic WHERE id=%s", (tid,))
    q('coop_settlement', "SELECT id,topic_id,coop_order_id,merchant_id,line_type,amount,status FROM coop_settlement ORDER BY id")
    q('coop_order after theme settlement', "SELECT id,to_id,status,share_mode,fixed_fee FROM coop_order WHERE topic_id=%s", (tid,))
    run_job(atok, 'settlementTask', 8)
    money_table('theme settled and job re-run', [s1, s2], MEM)
