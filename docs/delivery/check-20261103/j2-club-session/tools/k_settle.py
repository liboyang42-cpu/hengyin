"""K settlement (real jobs): FIXTURE TIME SHIFT ends theme 990014 eight days ago, then settlementTask (147) and
coopSettlementPayoutTask (148) run through the admin job endpoint. Read-back: coop_settlement lines and balances."""
from common import *
import time
cl.ex("UPDATE cms_topic SET end_date=DATE_SUB(NOW(), INTERVAL 8 DAY), sale_end_at=DATE_SUB(NOW(), INTERVAL 9 DAY) WHERE id=990014")
atok = cl.admin_token()
for jid in (147, 148):
    g = cl.q("SELECT job_group FROM sys_job WHERE job_id=%s", (jid,))[0][0]
    print(jid, J(cl.http("PUT", "/monitor/job/run", token=atok, json_body={"jobId": jid, "jobGroup": g}))[:100]); time.sleep(6)
print('topic', cl.q("SELECT id,is_settlement,fee_platform_rate,club_id,member_id FROM cms_topic WHERE id=990014"))
cols = [r[0] for r in cl.q("SELECT column_name FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='coop_settlement' ORDER BY ordinal_position")]
print('coop_settlement cols', cols)
for r in cl.q("SELECT * FROM coop_settlement WHERE topic_id=990014"): print(dict(zip(cols, r)))
print('balance', cl.q("SELECT member_id,event_type,event_id,change_balance,after_balance,change_reason FROM ums_member_balance_detail ORDER BY id DESC LIMIT 4"))
