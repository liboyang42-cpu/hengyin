"""Settlement (real job): FIXTURE TIME SHIFT moves activity 9 eight days into the past (settlement runs at end + 7 days),
then the platform runs the real settlementTask through the admin job endpoint. Read-back: balances and settlement rows."""
from common import *
import time
if 'noshift' not in sys.argv:
    cl.ex("UPDATE cms_activity SET start_date=DATE_SUB(start_date, INTERVAL 8 DAY), end_date=DATE_SUB(end_date, INTERVAL 8 DAY) WHERE id=9")
print('activity', cl.q("SELECT id,start_date,end_date,is_settlement,member_id,club_id FROM cms_activity WHERE id=9"))
atok = cl.admin_token()
for jid in (147,):
    g = cl.q("SELECT job_group FROM sys_job WHERE job_id=%s", (jid,))[0][0]
    print(jid, J(cl.http("PUT", "/monitor/job/run", token=atok, json_body={"jobId": jid, "jobGroup": g}))[:120])
time.sleep(6)
print('activity after', cl.q("SELECT id,is_settlement FROM cms_activity WHERE id=9"))
print('balance detail', cl.q("SELECT member_id,event_type,event_id,change_balance,after_balance,change_reason FROM ums_member_balance_detail ORDER BY id DESC LIMIT 6"))
print('settlement_detail', cl.q("SELECT * FROM settlement_detail ORDER BY id DESC LIMIT 4"))
