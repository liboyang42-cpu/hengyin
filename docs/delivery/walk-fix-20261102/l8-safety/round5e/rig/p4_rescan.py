"""Part 4: rescan job. Publish clean content, then import a made-up word that it contains (library version
changes), run the real Quartz job: report-only writes hits and removes nothing; with removal on it removes,
and a second run is idempotent. The removal switch is flipped through the real admin config endpoint."""
import sys, time
from proof_common import *

WORD = "qwpkzt"   # made-up token
atok = admin_token()
p4 = player(4)
wx_mode("pass")
JOB = q("SELECT job_id FROM sys_job WHERE invoke_target='sensitiveContentRescanTask.run()'")[0][0]

def run_job(times=3):
    for _ in range(times):
        r = http("PUT", "/monitor/job/run", token=atok, json_body={"jobId": JOB, "jobGroup": "DEFAULT"})
        time.sleep(3)
    return r

def set_remove(v):
    cfg = http("GET", "/system/config/124", token=atok).get("data")
    cfg["configValue"] = v
    return http("PUT", "/system/config", token=atok, json_body=cfg)

# public content published BEFORE the word exists
ct = "rig rescan comment %s target" % WORD
r = http("POST", "/api/comment/add", token=p4, form={"owner_type": "1", "owner_id": "990013", "rating": "5", "contents": ct})
cid = q("SELECT id FROM ums_comment WHERE contents=%s", (ct,))[0][0]
g = http("GET", "/api/v1/community/guidelines/active", token=p4)["data"]["id"]
c = http("POST", "/api/v1/community/posts", token=p4, json_body={"clientRequestId": "rig-l8-rescan-post", "body": "rig rescan post %s target" % WORD,
    "postType": "MOMENT", "audience": "PUBLIC", "commentPolicy": "EVERYONE", "guidelineVersionId": g, "mediaIds": [], "references": [], "locationPrecision": "NONE"})
post = c["data"].get("post") or c["data"]
http("POST", "/api/v1/community/posts/%s/publish" % post["id"], token=p4, json_body={"expectedVersion": post["version"], "requestId": "rig-l8-rescan-pub"})
pid = post["id"]
def comment_status(): return q("SELECT status, del_flag FROM ums_comment WHERE id=%s", (cid,))[0]
def post_life(): return q("SELECT lifecycle FROM community_post WHERE id=%s", (pid,))[0][0]
rec("setup: comment and post are public before the word exists", comment_status()[0] == 1 and post_life() == "PUBLISHED",
    comment=cid, comment_status=comment_status(), post=pid, lifecycle=post_life())

r = http("POST", "/business/sensitive/words/import", token=atok, json_body={"text": "%s,OTHER,BLOCK" % WORD})
rec("admin imports the made-up word afterwards", r.get("code") == 200 and r["data"]["imported"] == 1, resp=r)
rec("removal switch is off (default)", q("SELECT config_value FROM sys_config WHERE config_id=124")[0][0] == "false")
def hits(action): return q("SELECT content_type, content_id, category, action FROM content_sensitive_hit WHERE source='RESCAN' AND action=%s AND content_id IN (%s,%s) ORDER BY content_type", (action, cid, pid))
run_job()
rep = hits("REPORT")
rec("report-only run writes hits for both", {(x[0], x[1]) for x in rep} >= {("COMMENT", cid), ("COMMUNITY_POST", pid)}, hits=rep)
rec("report-only run removes nothing", comment_status()[0] == 1 and post_life() == "PUBLISHED", comment_status=comment_status(), lifecycle=post_life())
n = len(rep); run_job()
rec("report-only rerun adds no duplicate hits (same library version)", len(hits("REPORT")) == n, before=n, after=len(hits("REPORT")))

r = set_remove("true")
rec("admin turns removal on via /system/config", r.get("code") == 200 and q("SELECT config_value FROM sys_config WHERE config_id=124")[0][0] == "true", resp=r)
im0 = q("SELECT COUNT(*) FROM im_message WHERE receiver_id=9004")[0][0]
run_job()
rm = hits("REMOVED")
rec("removal run removes both and records REMOVED", {(x[0], x[1]) for x in rm} >= {("COMMENT", cid), ("COMMUNITY_POST", pid)}, hits=rm)
rec("comment no longer public", comment_status()[0] != 1, comment_status=comment_status())
rec("post no longer public", post_life() != "PUBLISHED", lifecycle=post_life())
rec("author notified", q("SELECT COUNT(*) FROM im_message WHERE receiver_id=9004")[0][0] > im0, before=im0,
    after=q("SELECT COUNT(*) FROM im_message WHERE receiver_id=9004")[0][0])
n = len(rm); s1 = (comment_status(), post_life()); run_job()
rec("removal rerun is idempotent (no new hits, state unchanged)", len(hits("REMOVED")) == n and (comment_status(), post_life()) == s1,
    before=n, after=len(hits("REMOVED")))
set_remove("false")
dump(sys.argv[1] if len(sys.argv) > 1 else "p4.json")
