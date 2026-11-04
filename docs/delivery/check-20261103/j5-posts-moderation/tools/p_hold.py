"""WeChat busy -> theme held (author-only) -> automatic recheck via the real Quartz job (admin /monitor/job/run).
Usage: p_hold.py create <name> | release <topicId> <pass|risky>
To avoid waiting 5 min the throwaway DB's next_attempt_at is moved to NOW() (fixture time shift, reported)."""
import sys, datetime, json
from common import *
p3 = tok("e2e-openid-9003"); atok = admin_token()
JOB = q("SELECT job_id FROM sys_job WHERE invoke_target='contentSafetyRecheckTask.run()'")[0][0]
def state(tid):
    return q("SELECT id,name,status,user_status,check_status FROM cms_topic WHERE id=%s", (tid,))
def recheck(tid):
    return q("SELECT id,state,attempt_count,last_error_code FROM content_safety_recheck WHERE biz_type='TOPIC' AND biz_id=%s ORDER BY id DESC LIMIT 1", (tid,))
if sys.argv[1] == "create":
    wx_mode("busy")
    D = datetime.datetime.now().replace(hour=9, minute=0, second=0, microsecond=0)
    d = lambda k: (D + datetime.timedelta(days=k)).strftime("%Y-%m-%d %H:%M:%S")
    dto = {"name": sys.argv[2], "subtitle": "j5", "description": "微信繁忙时提交的主题", "startDate": d(10), "endDate": d(40), "saleEndAt": d(30),
           "imgUrl": "https://rigbucket.oss-rig.invalid/upload/205dc125ee594333b2e681429bfef410.png",
           "categoryIds": "1", "productType": 2, "publishMode": "normal", "tickets": [], "clubId": 7001, "recruitDeadline": d(5), "openMerchantPool": 1,
           "chapters": [{"name": "第1章", "description": "j5", "schemaVersion": 1, "required": 1, "recruitEnabled": 1, "categoryId": 4001, "termsMode": "PERK", "perkMinValue": 30, "maxMerchant": 1,
                         "blocks": [{"type": "text", "content": "j5"}, {"type": "node", "nodeIndex": 0}],
                         "nodes": [{"name": "江边", "address": "上海市黄浦区", "longitude": "121.470000", "latitude": "31.230000", "nodeTime": 30}]}]}
    r = http("POST", "/api/topic/v2/create", token=p3, json_body=dto)
    wx_mode("pass")
    print("create", json.dumps(r, ensure_ascii=False)[:400])
    tid = (r.get("data") or {}).get("topicId")
    print("topic", state(tid), "recheck", recheck(tid))
else:
    tid = int(sys.argv[2]); wx_mode(sys.argv[3])
    ex("UPDATE content_safety_recheck SET next_attempt_at=NOW() WHERE biz_type='TOPIC' AND biz_id=%s AND state=0", (tid,))
    r = http("PUT", "/monitor/job/run", token=atok, json_body={"jobId": JOB, "jobGroup": "DEFAULT"})
    import time; time.sleep(4)
    wx_mode("pass")
    print("job", r.get("code"), "topic", state(tid), "recheck", recheck(tid))
    print("im to author", q("SELECT m.id,c.type,LEFT(m.content,80) FROM im_message m JOIN im_conversation c ON c.id=m.conversation_id WHERE m.receiver_id=9003 ORDER BY m.id DESC LIMIT 3"))
