"""Run the real Quartz contentSafetyRecheckTask once (admin /monitor/job/run) with WeChat answering <mode>.
Usage: p_release.py <BIZ_TYPE> <bizId> <pass|risky|busy>. next_attempt_at is moved to NOW() in the throwaway DB
so we don't wait 5 minutes (fixture time shift, reported)."""
import sys, time
from common import *
biz, bid, mode = sys.argv[1], int(sys.argv[2]), sys.argv[3]
JOB = q("SELECT job_id FROM sys_job WHERE invoke_target='contentSafetyRecheckTask.run()'")[0][0]
wx_mode(mode)
ex("UPDATE content_safety_recheck SET next_attempt_at=NOW() WHERE biz_type=%s AND biz_id=%s AND state=0", (biz, bid))
r = http("PUT", "/monitor/job/run", token=admin_token(), json_body={"jobId": JOB, "jobGroup": "DEFAULT"})
time.sleep(4); wx_mode("pass")
print("job", r.get("code"))
print("recheck", q("SELECT id,state,attempt_count,last_error_code FROM content_safety_recheck WHERE biz_type=%s AND biz_id=%s ORDER BY id DESC LIMIT 1", (biz, bid)))
tbl = {"ACTIVITY": "SELECT id,name,status,publish_status,check_status FROM cms_activity WHERE id=%s",
       "TOPIC": "SELECT id,name,status,user_status,check_status FROM cms_topic WHERE id=%s"}.get(biz)
if tbl: print("row", q(tbl, (bid,)))
