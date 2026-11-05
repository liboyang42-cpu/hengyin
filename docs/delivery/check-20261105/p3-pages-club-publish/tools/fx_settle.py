"""FIXTURE (time shift, labelled) + real jobs: move club session activity 15 nine days into the past (8-day settlement
window + 1 because the rig's MySQL runs in UTC), as f2 did for activity 9, then run the real scheduled jobs
through the real admin endpoint PUT /monitor/job/run. No money row is written by hand."""
import sys
from fx_common import *
ACT = int(sys.argv[1]); JOBS = [int(x) for x in sys.argv[2:]]
cl.ex("UPDATE cms_activity SET start_date=DATE_SUB(start_date, INTERVAL 9 DAY), end_date=DATE_SUB(end_date, INTERVAL 9 DAY) WHERE id=%s", (ACT,))
cl.ex("UPDATE oms_ticket SET start_time=DATE_SUB(start_time, INTERVAL 9 DAY), end_time=DATE_SUB(end_time, INTERVAL 9 DAY) WHERE owner_type=2 AND owner_id=%s", (ACT,))
cl.ex("UPDATE club_event_occurrence SET occurrence_at=DATE_SUB(occurrence_at, INTERVAL 9 DAY) WHERE activity_id=%s", (ACT,))
print(cl.q("SELECT id,start_date,end_date FROM cms_activity WHERE id=%s", (ACT,)))
atok = cl.admin_token()
for j in JOBS:
    g = cl.q("SELECT job_group FROM sys_job WHERE job_id=%s", (j,))[0][0]
    r = cl.http("PUT", "/monitor/job/run", token=atok, json_body={"jobId": j, "jobGroup": g})
    print('job', j, J(r)[:200])
