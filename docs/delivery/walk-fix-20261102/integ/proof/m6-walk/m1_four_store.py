"""Round 13 m6, M1 on the rig: the CI chain's four-store 探店日 (FOUR_STORE_REDEEM, platform content slot, theme club 7001,
session club 7002, one player paid ¥69 and scanned at four shops). TIME SHIFT on this run's own chain rows only (the session
and its tickets' frozen refund deadline), then the real settlement job. Expected at head: the job FAILS and names the
session, the ¥69 and the reason; no money moves; the session stays unsettled."""
from common import *
atok = admin()
tid = cl.q("SELECT id FROM cms_topic WHERE product_type=2 AND id IN (SELECT topic_id FROM topic_sale_snapshot WHERE JSON_UNQUOTE(JSON_EXTRACT(canonical_json,'$.completionPolicy.evidenceRule'))='FOUR_STORE_REDEEM') ORDER BY id DESC LIMIT 1")[0][0]
q('[M1] four-store theme', "SELECT t.id,t.club_id,t.member_id,t.lifecycle,(SELECT GROUP_CONCAT(CONCAT(slot,':',entity_type)) FROM product_role p WHERE p.topic_id=t.id AND p.status=1) FROM cms_topic t WHERE t.id=%s", (tid,))
acts = [r[0] for r in cl.q("SELECT DISTINCT owner_id FROM cms_registration WHERE owner_type=2 AND payment_status=2 AND owner_id IN (SELECT id FROM cms_activity WHERE topic_id=%s)", (tid,))]
ids = ','.join(map(str, acts))
q('[M1] paid sessions and tickets', "SELECT r.id,r.owner_id,a.club_id,r.member_id,r.payable_amount,r.payment_status,r.verification_status FROM cms_registration r JOIN cms_activity a ON a.id=r.owner_id WHERE r.owner_type=2 AND a.topic_id=%s ORDER BY r.id", (tid,))
for act in acts:
    ex("UPDATE cms_activity SET start_date=DATE_SUB(DATE_ADD(NOW(), INTERVAL 8 HOUR), INTERVAL 15 DAY) - INTERVAL 3 HOUR, end_date=DATE_SUB(DATE_ADD(NOW(), INTERVAL 8 HOUR), INTERVAL 15 DAY) WHERE id=%s", (act,))
    ex("UPDATE cms_registration SET refund_deadline=(SELECT start_date FROM cms_activity WHERE id=%s) WHERE owner_type=2 AND owner_id=%s AND refund_deadline IS NOT NULL", (act, act))
note('[M1] TIME SHIFT (own chain rows): sessions %s of theme %s now ended 15 days ago; their tickets\' refund deadline moved with them' % (ids, tid))
before = cl.q("SELECT COUNT(*),COALESCE(SUM(change_balance),0) FROM ums_member_balance_detail")[0]
run_job(atok, 'settlementTask', 8)
q('[M1] job log exception (the alarm text)', "SELECT job_log_id,status,LEFT(exception_info,600) FROM sys_job_log WHERE job_name LIKE %s ORDER BY job_log_id DESC LIMIT 1", ('分润结算%',))
q('[M1] sessions after the job', "SELECT id,is_settlement FROM cms_activity WHERE id IN (%s)" % ids)
after = cl.q("SELECT COUNT(*),COALESCE(SUM(change_balance),0) FROM ums_member_balance_detail")[0]
log('[M1] balance detail rows/sum before vs after the job', [list(before), list(after)])
q('[M1] edition FINAL rows', "SELECT COUNT(*) FROM topic_edition_finalization WHERE topic_id=%s", (tid,))
