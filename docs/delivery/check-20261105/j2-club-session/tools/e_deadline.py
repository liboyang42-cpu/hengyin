"""E (job timing, labelled TIME SHIFT): move recruit_deadline into the past so the real topicRecruitDeadlineTask picks the theme up now."""
from common import *
import time
cl.ex("UPDATE cms_topic SET recruit_deadline=DATE_SUB(NOW(), INTERVAL 1 DAY) WHERE id=990014")
atok = cl.admin_token()
jid = cl.q("SELECT job_id FROM sys_job WHERE invoke_target LIKE 'topicRecruitDeadlineTask%%'")[0][0]
g = cl.q("SELECT job_group FROM sys_job WHERE job_id=%s", (jid,))[0][0]
print(jid, J(cl.http("PUT", "/monitor/job/run", token=atok, json_body={"jobId": jid, "jobGroup": g})))
time.sleep(5)
print(cl.q("SELECT id,lifecycle,recruit_deadline,fee_platform_rate FROM cms_topic WHERE id=990014"))
print(cl.q("SELECT job_log_id,status,LEFT(job_message,80),LEFT(exception_info,200) FROM sys_job_log ORDER BY job_log_id DESC LIMIT 1"))
