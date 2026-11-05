"""Community post outside clubs, same call sequence the app's community client sends for the roam 发到广场 share
(utils/community-submit-progress.js): guidelines/active → guidelines/ack → POST /posts → POST /posts/{id}/publish.
Word / split word / allow phrase / WeChat busy as player 9004. Read back lifecycle."""
import json, uuid, sys
from common import *
t = tok(sys.argv[1] if len(sys.argv) > 1 else "e2e-openid-9004")
B = "/api/v1/community"
g = http("GET", B + "/guidelines/active", token=t); gid = (g.get("data") or g).get("id")
print("guideline", gid, http("POST", B + "/guidelines/ack", token=t, json_body={"guidelineVersionId": gid, "requestId": uuid.uuid4().hex, "scene": "PUBLISH"}).get("code"))
cases = [("word", "今天走了江边 zqvxlm", "pass"), ("split", "今天走了江边 z-q.v x_l*m", "pass"),
         ("allow", "今天走了江边 zqvxlmpark", "pass"), ("busy", "今天走了江边 繁忙时发的", "busy")]
for label, text, mode in cases:
    body = {"clientRequestId": uuid.uuid4().hex, "body": text, "postType": "MOMENT", "audience": "PUBLIC", "commentPolicy": "EVERYONE",
            "guidelineVersionId": gid, "mediaIds": [], "references": [], "locationPrecision": "NONE"}
    wx_mode(mode)
    c = http("POST", B + "/posts", token=t, json_body=body)
    d = c.get("data") or {}; pid = (d.get("post") or d).get("id") if isinstance(d, dict) else None
    ver = (d.get("post") or d).get("version") if isinstance(d, dict) else None
    p = http("POST", B + "/posts/%s/publish" % pid, token=t, json_body={"expectedVersion": ver, "requestId": uuid.uuid4().hex}) if pid else {}
    wx_mode("pass")
    print(label, "create:", c.get("code"), c.get("msg"), "| publish:", p.get("code"), p.get("msg"), "| id", pid)
    if pid: print("   row", q("SELECT id,lifecycle,LEFT(body,40) FROM community_post WHERE id=%s", (pid,)))
