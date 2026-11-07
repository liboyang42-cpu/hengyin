"""Part 2: WeChat check unavailable -> author-only hold -> automatic recheck (pass / violation / exhausted).
Recheck runs through the real Quartz job (admin PUT /monitor/job/run). To avoid waiting 5 min / 16 h the
proof moves next_attempt_at to now (and, for 'exhausted', max_attempts to 1) in the throwaway DB."""
import sys, time, datetime
from proof_common import *

atok = admin_token()
p3, p4 = player(3), player(4)
JOB = q("SELECT job_id FROM sys_job WHERE invoke_target='contentSafetyRecheckTask.run()'")[0][0]
D = datetime.datetime.now().replace(hour=9, minute=0, second=0, microsecond=0)
d = lambda k: (D + datetime.timedelta(days=k)).strftime("%Y-%m-%d %H:%M:%S")

def topic(name):
    dto = {"name": name, "subtitle": "rig", "description": "rig recheck " + name, "startDate": d(10), "endDate": d(40),
           "saleEndAt": d(30), "categoryIds": "1", "productType": 2, "publishMode": "normal", "tickets": [], "clubId": 7001,
           "recruitDeadline": d(5), "openMerchantPool": 1,
           "chapters": [{"name": "第1章", "description": "rig", "schemaVersion": 1, "required": 1, "recruitEnabled": 1,
                         "categoryId": 4001, "termsMode": "PERK", "perkMinValue": 30, "maxMerchant": 1,
                         "blocks": [{"type": "text", "content": "rig"}, {"type": "node", "nodeIndex": 0}],
                         "nodes": [{"name": "rig 点", "address": "上海市黄浦区", "longitude": "121.470000", "latitude": "31.230000", "nodeTime": 30}]}]}
    r = http("POST", "/api/topic/v2/create", token=p3, json_body=dto)
    rows = q("SELECT id FROM cms_topic WHERE name=%s ORDER BY id DESC LIMIT 1", (name,))
    return r, (rows[0][0] if rows else None)

def public_ids(tok):
    r = http("POST", "/api/topic/list", token=tok, form={"is_my": "0"})
    rows = (r.get("data") or {}).get("list") if isinstance(r.get("data"), dict) else r.get("rows") or r.get("data") or []
    return {int(x.get("id")) for x in rows or [] if isinstance(x, dict) and x.get("id")}

def visible(tok, tid):
    r = http("POST", "/api/topic/info-to-user", token=tok, form={"id": str(tid)})
    return r.get("code") == 200 and bool(r.get("data")), r.get("msg")

def run_job():
    r = http("PUT", "/monitor/job/run", token=atok, json_body={"jobId": JOB, "jobGroup": "DEFAULT"})
    time.sleep(4)
    return r

def state(tid):
    return q("SELECT user_status, check_status FROM cms_topic WHERE id=%s", (tid,))[0]

def recheck(tid):
    return q("SELECT state, attempt_count, last_error_code FROM content_safety_recheck WHERE biz_type='TOPIC' AND biz_id=%s ORDER BY id DESC LIMIT 1", (tid,))

def im_count():
    return q("SELECT COUNT(*) FROM im_message WHERE receiver_id=9003")[0][0]

def free_quota():
    for (old,) in q("SELECT id FROM cms_topic WHERE member_id=9003 AND user_status=1 AND name LIKE 'rig %%'"):
        http("POST", "/api/topic/update_user_status?id=%s&expectedUserStatus=1" % old, token=p3)

def hold(name):
    free_quota()
    wx_mode("busy")
    r, tid = topic(name)
    rec("[%s] create while WeChat busy is accepted but held" % name, tid is not None and state(tid)[0] == 0,
        resp=r, topic=tid, topic_state=state(tid) if tid else None, recheck=recheck(tid) if tid else None)
    rec("[%s] queued for automatic recheck" % name, recheck(tid) and recheck(tid)[0][0] == 0, recheck=recheck(tid))
    rec("[%s] not on public list (player 9004)" % name, tid not in public_ids(p4))
    rec("[%s] not visible to another player" % name, not visible(p4, tid)[0], resp=visible(p4, tid))
    rec("[%s] visible to its author" % name, visible(p3, tid)[0], resp=visible(p3, tid))
    return tid

# A: recheck passes -> published
tid = hold("rig 复检通过")
wx_mode("pass")
ex("UPDATE content_safety_recheck SET next_attempt_at=NOW() WHERE biz_type='TOPIC' AND biz_id=%s AND state=0", (tid,))
r = run_job()
rec("[pass] job run", r.get("code") == 200, resp=r)
rec("[pass] recheck row state=1 PASS", recheck(tid) and recheck(tid)[0][0] == 1, recheck=recheck(tid))
rec("[pass] topic published (user_status=1)", state(tid)[0] == 1, topic_state=state(tid))
rec("[pass] now visible to other players", visible(p4, tid)[0], resp=visible(p4, tid))

# B: recheck finds a violation -> rejected, stays hidden, author notified
tid = hold("rig 复检违规")
n0 = im_count()
wx_mode("risky")
ex("UPDATE content_safety_recheck SET next_attempt_at=NOW() WHERE biz_type='TOPIC' AND biz_id=%s AND state=0", (tid,))
run_job()
rec("[risky] recheck row state=2 REJECTED", recheck(tid) and recheck(tid)[0][0] == 2, recheck=recheck(tid))
rec("[risky] topic stays hidden", state(tid)[0] != 1 and not visible(p4, tid)[0], topic_state=state(tid))
rec("[risky] author notified (im_message to 9003)", im_count() > n0, before=n0, after=im_count(),
    last=q("SELECT msg_type, LEFT(content,60) FROM im_message WHERE receiver_id=9003 ORDER BY id DESC LIMIT 1"))

# C: retries exhausted -> hidden, author notified
tid = hold("rig 复检耗尽")
n0 = im_count()
wx_mode("busy")
ex("UPDATE content_safety_recheck SET next_attempt_at=NOW(), max_attempts=1 WHERE biz_type='TOPIC' AND biz_id=%s AND state=0", (tid,))
run_job()
rec("[exhausted] recheck row state=3 EXHAUSTED", recheck(tid) and recheck(tid)[0][0] == 3, recheck=recheck(tid))
rec("[exhausted] topic stays hidden", state(tid)[0] != 1 and not visible(p4, tid)[0], topic_state=state(tid))
rec("[exhausted] author notified", im_count() > n0, before=n0, after=im_count(),
    last=q("SELECT msg_type, LEFT(content,60) FROM im_message WHERE receiver_id=9003 ORDER BY id DESC LIMIT 1"))

# D: busy once more, recheck still busy -> rescheduled with backoff, still hidden
tid = hold("rig 复检重试")
ex("UPDATE content_safety_recheck SET next_attempt_at=NOW() WHERE biz_type='TOPIC' AND biz_id=%s AND state=0", (tid,))
run_job()
rc = q("SELECT state, attempt_count, last_error_code, TIMESTAMPDIFF(MINUTE, NOW(), next_attempt_at) FROM content_safety_recheck WHERE biz_type='TOPIC' AND biz_id=%s", (tid,))
rec("[retry] still busy -> rescheduled with backoff, hidden", rc and rc[0][0] == 0 and rc[0][1] == 1 and rc[0][3] >= 8 and state(tid)[0] == 0, recheck=rc)
wx_mode("pass")
dump(sys.argv[1] if len(sys.argv) > 1 else "p2.json")
