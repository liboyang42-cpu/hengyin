"""FIXTURE TIME SHIFT + real job: activity 14 moved 8 days into the past (settlement runs at end + 7 days), then the
platform runs the real settlementTask through the admin job endpoint (as j1 F1 / j2 i_settle did)."""
exec(open('/root/p2/fx_common.py').read())
import time
#cl.ex("UPDATE cms_activity SET start_date=DATE_SUB(start_date, INTERVAL 11 DAY), end_date=DATE_SUB(end_date, INTERVAL 11 DAY) WHERE id=14")
print('activity', cl.q("SELECT id,start_date,end_date,is_settlement FROM cms_activity WHERE id=14"))
print('cms_setting', cl.q("SELECT id FROM cms_setting LIMIT 3"))
atok = cl.admin_token()
jobs = cl.q("SELECT job_id,job_name,job_group,invoke_target,status FROM sys_job WHERE invoke_target LIKE 'settlementTask%%'")
print(jobs)
for jid, name, g, tgt, st in jobs:
    print(jid, json.dumps(cl.http("PUT", "/monitor/job/run", token=atok, json_body={"jobId": jid, "jobGroup": g}), ensure_ascii=False)[:160])
time.sleep(8)
print('activity after', cl.q("SELECT id,is_settlement FROM cms_activity WHERE id=14"))
print('balance', cl.q("SELECT member_id,change_balance,after_balance,change_reason FROM ums_member_balance_detail ORDER BY id DESC LIMIT 4"))
print('sd', cl.q("SELECT id,source_type,source_id,receiver_id,gross_amount,settlement_amount,status FROM settlement_detail ORDER BY id DESC LIMIT 6"))
