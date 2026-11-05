"""Rescan of published text (first-pass H3): club lead 9003 has a live club post with made-up word qwpkzt; the word is
then imported; the real sensitiveContentRescanTask runs (admin /monitor/job/run) in report mode, then with
content.sensitive.rescan.remove switched on through the real /system/config API (switched back off at the end)."""
import time, json
from common import *
atok = admin_token(); W2 = "qwpkzt"; p3 = tok("e2e-openid-9003")
JOB = q("SELECT job_id FROM sys_job WHERE invoke_target='sensitiveContentRescanTask.run()'")[0][0]
CID = q("SELECT config_id FROM sys_config WHERE config_key='content.sensitive.rescan.remove'")[0][0]
def run_job(n=3):
    for _ in range(n): r = http("PUT", "/monitor/job/run", token=atok, json_body={"jobId": JOB, "jobGroup": "DEFAULT"}); time.sleep(3)
    return r.get("code")
def show(): print(" club_post", q("SELECT id,del_flag,content FROM club_post WHERE content LIKE %s", ("%" + W2 + "%",)))
print("post", http("POST", "/api/club/post/create", token=p3, json_body={"clubId": 7001, "content": "周末江边集合 " + W2, "postType": "post"}).get("msg"))
print("import", http("POST", "/business/sensitive/words/import", token=atok, json_body={"text": "%s,OTHER,BLOCK" % W2}).get("data"))
print("switch", q("SELECT config_value FROM sys_config WHERE config_id=%s", (CID,)))
print("job(report)", run_job()); show()
cfg = http("GET", "/system/config/%s" % CID, token=atok).get("data"); cfg["configValue"] = "true"
print("switch on", http("PUT", "/system/config", token=atok, json_body=cfg).get("code"))
print("job(remove)", run_job()); show()
print("hits", q("SELECT id,source,content_type,content_id,action FROM content_sensitive_hit WHERE source='RESCAN' ORDER BY id"))
print("notice to 9003", q("SELECT m.id,LEFT(m.content,200) FROM im_message m WHERE m.receiver_id=9003 ORDER BY m.id DESC LIMIT 2"))
cfg["configValue"] = "false"; print("switch off", http("PUT", "/system/config", token=atok, json_body=cfg).get("code"), q("SELECT config_value FROM sys_config WHERE config_id=%s", (CID,)))
