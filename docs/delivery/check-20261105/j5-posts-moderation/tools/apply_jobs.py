"""Apply the candidate's post-deploy migration_20261104_content_safety_jobs_post_deploy.sql (two paused sys_job rows)
to the throwaway rig DB: the rig schema stage leaves POST_DEPLOY files out."""
import re
from common import *
sql = open(CAND + "/chengyinhub-admin/sql/migration_20261104_content_safety_jobs_post_deploy.sql", encoding="utf-8").read()
for stmt in re.findall(r"INSERT INTO sys_job.*?\);", sql, re.S):
    ex(stmt.replace('%', '%%'))
print(q("SELECT job_id,job_name,invoke_target,status FROM sys_job WHERE invoke_target IN ('contentSafetyRecheckTask.run()','sensitiveContentRescanTask.run()')"))
