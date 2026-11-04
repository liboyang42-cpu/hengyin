"""Published-content rescan: import made-up word qwpkzt (already in a live club post and a chat message), run the real
sensitiveContentRescanTask via admin /monitor/job/run. Stage 'report' (default switch false) then 'remove' (switch on
through the real /system/config endpoint)."""
import sys, time
from common import *
atok = admin_token(); W2 = "qwpkzt"
JOB = q("SELECT job_id FROM sys_job WHERE invoke_target='sensitiveContentRescanTask.run()'")[0][0]
def run_job(times=3):
    for _ in range(times):
        r = http("PUT", "/monitor/job/run", token=atok, json_body={"jobId": JOB, "jobGroup": "DEFAULT"}); time.sleep(3)
    return r
def show():
    print(" club_post", q("SELECT id,del_flag,content FROM club_post WHERE content LIKE %s", ("%" + W2 + "%",)))
    print(" im", q("SELECT id,status,check_status,content FROM im_message WHERE content LIKE %s", ("%" + W2 + "%",)))
    print(" hits", q("SELECT id,source,content_type,content_id,action FROM content_sensitive_hit WHERE source='RESCAN' ORDER BY id"))
if sys.argv[1] == "report":
    print("import", http("POST", "/business/sensitive/words/import", token=atok, json_body={"text": "%s,OTHER,BLOCK" % W2}))
    print("switch", q("SELECT config_value FROM sys_config WHERE config_id=124"))
    print("job", run_job().get("code")); show()
else:
    cfg = http("GET", "/system/config/124", token=atok).get("data"); cfg["configValue"] = "true"
    print("switch on", http("PUT", "/system/config", token=atok, json_body=cfg).get("code"), q("SELECT config_value FROM sys_config WHERE config_id=124"))
    print("job", run_job().get("code")); show()
    print("im to 9004", q("SELECT m.id,c.type,LEFT(m.content,120) FROM im_message m JOIN im_conversation c ON c.id=m.conversation_id WHERE m.receiver_id=9004 AND c.type=2 ORDER BY m.id DESC LIMIT 3"))
