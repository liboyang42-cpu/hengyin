"""Part 1: library hit refuses comment / post / theme; allow-listed phrase passes; symbol split caught;
external image URL refused. Made-up tokens only, imported through the real admin endpoint."""
import sys, datetime
from proof_common import *

WORD = "zqvxlm"            # made-up token, not a real word
ALLOW = "zqvxlmpark"       # made-up harmless phrase containing it
wx_mode("pass")
atok = admin_token()
r = http("POST", "/business/sensitive/words/import", token=atok, json_body={"text": "# rig made-up\n%s,OTHER,BLOCK" % WORD})
rec("admin import word", r.get("code") == 200 and r["data"]["imported"] == 1, resp=r)
r = http("POST", "/business/sensitive/allows/import", token=atok, json_body={"text": ALLOW})
rec("admin import allow phrase", r.get("code") == 200 and r["data"]["imported"] == 1, resp=r)
rec("db word row", q("SELECT word,category,action,enabled FROM content_sensitive_word WHERE word=%s", (WORD,)) == ((WORD, "OTHER", "BLOCK", 1),))
rec("db allow row", q("SELECT phrase,enabled FROM content_sensitive_allow WHERE phrase=%s", (ALLOW,)) == ((ALLOW, 1),))

p4 = player(4)
TOPIC = 990013
def comment(text, img=None):
    form = {"owner_type": "1", "owner_id": str(TOPIC), "rating": "5", "contents": text}
    if img: form["img_arr"] = img
    return http("POST", "/api/comment/add", token=p4, form=form)
def ncomments(text):
    return q("SELECT COUNT(*) FROM ums_comment WHERE contents=%s", (text,))[0][0]

hits0 = q("SELECT COUNT(*) FROM content_sensitive_hit WHERE source='TEXT'")[0][0]
t = "rig comment hit %s here" % WORD
r = comment(t)
rec("comment with library word refused", r.get("code") != 200 and ncomments(t) == 0, resp=r, rows=ncomments(t))
t = "rig comment split z.q-v x*l_m here"
r = comment(t)
rec("comment with symbol-split word refused", r.get("code") != 200 and ncomments(t) == 0, resp=r, rows=ncomments(t))
t = "rig comment allow %s here" % ALLOW
r = comment(t)
rec("comment with allow-listed phrase passes", r.get("code") == 200 and ncomments(t) == 1, resp_code=r.get("code"), rows=ncomments(t))
t = "rig comment external image"
r = comment(t, "https://example.invalid/x.jpg")
rec("comment with external image URL refused", r.get("code") != 200 and ncomments(t) == 0, resp=r, rows=ncomments(t))
hits1 = q("SELECT COUNT(*) FROM content_sensitive_hit WHERE source='TEXT'")[0][0]
rec("hit rows recorded (type/id/category only)", hits1 - hits0 >= 2, before=hits0, after=hits1,
    sample=q("SELECT source,content_type,content_id,category,action FROM content_sensitive_hit ORDER BY id DESC LIMIT 2"))

# community post (/api/v1/community)
g = http("GET", "/api/v1/community/guidelines/active", token=p4)
gid = (g.get("data") or {}).get("id")
http("POST", "/api/v1/community/guidelines/ack", token=p4, json_body={"guidelineVersionId": gid, "requestId": "rig-l8-ack-1", "scene": "PUBLISH"})
def post(text, key):
    c = http("POST", "/api/v1/community/posts", token=p4, json_body={"clientRequestId": key, "body": text, "postType": "MOMENT",
        "audience": "PUBLIC", "commentPolicy": "EVERYONE", "guidelineVersionId": gid, "mediaIds": [], "references": [], "locationPrecision": "NONE"})
    if c.get("code") != 200:
        return c, None
    pid = (c["data"].get("post") or c["data"]).get("id")
    ver = (c["data"].get("post") or c["data"]).get("version")
    p = http("POST", "/api/v1/community/posts/%s/publish" % pid, token=p4, json_body={"expectedVersion": ver, "requestId": key + "-pub"})
    return p, pid
r, pid = post("rig post hit %s here" % WORD, "rig-l8-post-hit")
life = q("SELECT lifecycle FROM community_post WHERE id=%s", (pid,)) if pid else ()
rec("community post with library word refused", r.get("code") != 200 and (not life or life[0][0] != "PUBLISHED"), resp=r, post=pid, lifecycle=life)
r, pid = post("rig post allow %s here" % ALLOW, "rig-l8-post-allow")
life = q("SELECT lifecycle FROM community_post WHERE id=%s", (pid,)) if pid else ()
rec("community post with allow phrase publishes", r.get("code") == 200 and life and life[0][0] == "PUBLISHED", resp_code=r.get("code"), post=pid, lifecycle=life)

# theme (topic v2 create) by club host 9003, same DTO shape as chain_platform
p3 = player(3)
D = datetime.datetime.now().replace(hour=9, minute=0, second=0, microsecond=0)
d = lambda k: (D + datetime.timedelta(days=k)).strftime("%Y-%m-%d %H:%M:%S")
def topic(name, desc):
    dto = {"name": name, "subtitle": "rig", "description": desc, "startDate": d(10), "endDate": d(40), "saleEndAt": d(30),
           "categoryIds": "1", "productType": 2, "publishMode": "normal", "tickets": [], "clubId": 7001, "recruitDeadline": d(5), "openMerchantPool": 1,
           "chapters": [{"name": "第1章", "description": "rig", "schemaVersion": 1, "required": 1, "recruitEnabled": 1, "categoryId": 4001, "termsMode": "PERK", "perkMinValue": 30, "maxMerchant": 1,
                         "blocks": [{"type": "text", "content": "rig"}, {"type": "node", "nodeIndex": 0}],
                         "nodes": [{"name": "rig 点", "address": "上海市黄浦区", "longitude": "121.470000", "latitude": "31.230000", "nodeTime": 30}]}]}
    return http("POST", "/api/topic/v2/create", token=p3, json_body=dto)
st = http("POST", "/api/publisher/identity/status", token=p3, json_body={})
if not ((st.get("data") or {}).get("registered")):
    http("POST", "/api/publisher/identity", token=p3, json_body={"realName": "测试主理人", "idCard": "110101199001010015", "consent": True, "source": "topic_publish"})
name = "rig 主题 hit"
r = topic(name, "简介 %s 结尾" % WORD)
rows = q("SELECT id,user_status FROM cms_topic WHERE name=%s", (name,))
rec("theme with library word refused", r.get("code") != 200 and rows == (), resp=r, rows=rows)
name = "rig 主题 clean"
r = topic(name, "简介 %s 结尾" % ALLOW)
rows = q("SELECT id,user_status FROM cms_topic WHERE name=%s", (name,))
rec("control: same theme with allow phrase is accepted", r.get("code") == 200 and len(rows) == 1, resp=r, rows=rows)
dump(sys.argv[1] if len(sys.argv) > 1 else "p1.json")
