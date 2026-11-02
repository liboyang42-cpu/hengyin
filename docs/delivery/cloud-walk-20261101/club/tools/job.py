import sys, time; sys.path.insert(0, '/root/sc')
from common import *
atok = cl.admin_token()
for jid in sys.argv[1:]:
    g = cl.q("SELECT job_group FROM sys_job WHERE job_id=%s", (int(jid),))[0][0]
    print(jid, J(cl.http("PUT", "/monitor/job/run", token=atok, json_body={"jobId": int(jid), "jobGroup": g}))[:120])
time.sleep(4)
