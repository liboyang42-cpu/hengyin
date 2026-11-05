"""Settlement (real jobs). FIXTURE TIME SHIFT: activity 9 moved 9 days back (settlement runs at end + 7 days; +1 because the
rig MySQL NOW() is UTC while activity times are Shanghai wall time). Then the platform runs the real jobs through the admin
job endpoint. Read-back: balances, balance events, settlement_detail, coop_settlement."""
from common import *
import time
q = lambda s, a=None: print(s.split(' FROM')[0][:60], '->', cl.q(s, a))
if 'noshift' not in sys.argv:
    cl.ex("UPDATE cms_activity SET start_date=DATE_SUB(start_date, INTERVAL 9 DAY), end_date=DATE_SUB(end_date, INTERVAL 9 DAY) WHERE id=9")
q("SELECT id,start_date,end_date,is_settlement FROM cms_activity WHERE id=9")
q("SELECT id,balance FROM ums_member WHERE id IN (9001,9002,990005,990006) ORDER BY id")
atok = cl.admin_token()
for jid in (145, 146):
    g = cl.q("SELECT job_group FROM sys_job WHERE job_id=%s", (jid,))[0][0]
    print(jid, cl.q("SELECT job_name FROM sys_job WHERE job_id=%s", (jid,))[0][0], J(cl.http("PUT", "/monitor/job/run", token=atok, json_body={"jobId": jid, "jobGroup": g}))[:100])
    time.sleep(6)
q("SELECT job_log_id,job_name,status,LEFT(exception_info,200) FROM sys_job_log ORDER BY job_log_id DESC LIMIT 2")
q("SELECT id,is_settlement FROM cms_activity WHERE id=9")
q("SELECT id,balance FROM ums_member WHERE id IN (9001,9002,990005,990006) ORDER BY id")
q("SELECT member_id,event_type,event_id,change_balance,after_balance,change_reason FROM ums_member_balance_detail ORDER BY id")
q("SELECT * FROM settlement_detail ORDER BY id")
q("SELECT id,coop_order_id,status FROM coop_settlement")
