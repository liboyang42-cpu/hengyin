#!/usr/bin/env python3
"""p1 second pass fixtures. Every write is real HTTP as the acting fictional user against the throwaway rig.
Prints each response so the report can cite it. Run with $RIG_HOME/venv/bin/python fixtures.py <step>."""
import json, sys, uuid
from fx import call, cl
def show(label, r): print(label, json.dumps(r, ensure_ascii=False, default=str)[:700])
def rid(tag): return ("p1fx-" + tag + "-" + uuid.uuid4().hex)[:48]
def post(who, body, tag):
    r = call(who, "POST", "/api/v1/community/posts", {"clientRequestId": rid(tag), "postType": "MOMENT", "body": body,
        "audience": "PUBLIC", "commentPolicy": "EVERYONE", "locationPrecision": "NONE", "disclosureType": "NONE", "guidelineVersionId": 1})
    show("create " + tag, r)
    d = r.get("data") or {}; p = d.get("post") or d
    pid, ver = p.get("id"), p.get("version")
    if pid:
        show("publish " + tag, call(who, "POST", "/api/v1/community/posts/%s/publish" % pid, {"expectedVersion": ver, "requestId": rid(tag + "p")}))
    return pid
step = sys.argv[1]
if step == "posts":
    a = post("e2e-openid-9009", "周末沿着苏州河走了一圈，傍晚的光线很好。", "9009a")
    b = post("e2e-openid-9009", "第二次走外滩支线，记了几个适合拍照的转角。", "9009b")
    c = post("e2e-openid-9004", "新天地附近有家安静的咖啡店，下午人不多。", "9004a")
    print("POSTS", a, b, c)
elif step == "social":
    a, b, c = sys.argv[2:5]
    show("9004 like 9009a", call("e2e-openid-9004", "POST", "/api/v1/community/posts/%s/actions" % a, {"type": "LIKE", "requestId": rid("lk")}))
    show("9004 comment 9009a", call("e2e-openid-9004", "POST", "/api/v1/community/posts/%s/comments" % a, {"body": "这条路线我也想走一次。", "clientRequestId": rid("cm"), "requestId": rid("cm2")}))
    show("9009 bookmark 9004a", call("e2e-openid-9009", "POST", "/api/v1/community/posts/%s/actions" % c, {"type": "BOOKMARK", "requestId": rid("bm")}))
    show("9009 like 9004a", call("e2e-openid-9009", "POST", "/api/v1/community/posts/%s/actions" % c, {"type": "LIKE", "requestId": rid("lk2")}))
    show("9004 follow 9009", call("e2e-openid-9004", "POST", "/api/user/follow/action", None, {"member_id": "9009", "type": "1"}) if False else cl.http("POST", "/api/user/follow/action", token=__import__('fx').token("e2e-openid-9004"), form={"follow_member_id": "9009", "member_id": "9009"}))
elif step == "im":
    r = cl.http("POST", "/api/im/start", token=__import__('fx').token("e2e-openid-9004"), form={"target_member_id": "9009"}); show("9004 start im", r)
    cid = str((r.get("data") or {}).get("conversationId"))
    for who, txt in (("e2e-openid-9004", "你好，周六那场还有位置吗？"), ("e2e-openid-9009", "有的，我已经报名了，可以一起。"), ("e2e-openid-9004", "好，那到时见。")):
        show("send", cl.http("POST", "/api/im/send", token=__import__('fx').token(who), form={"conversation_id": cid, "msg_type": "1", "content": txt, "client_message_id": rid("m")}))
    print("CONV", cid)
elif step == "team":
    r = call("e2e-openid-9009", "POST", "/api/team/create", {"ownerType": 0, "ownerId": 0, "title": "周末夜行小队", "maxMembers": 4, "joinMode": 1}); show("9009 team create", r)
    t = (r.get("data") or {}).get("team") or {}
    print("TEAM", t.get("id"), t.get("inviteCode"))
    if t.get("inviteCode"):
        show("9004 join", call("e2e-openid-9004", "POST", "/api/team/join", {"inviteCode": t.get("inviteCode")}))
elif step == "capsule":
    show("capsule", call("e2e-openid-9009", "POST", "/api/capsules/create", {"requestId": rid("cap"), "body": "一年后再看今天走过的路。", "unlockDate": "2027-11-05"}))
    show("goal", call("e2e-openid-9009", "POST", "/api/exploration-goals/create", {"requestId": rid("goal"), "metric": "DISTANCE_KM", "target": 20, "startDate": "2026-11-01", "endDateExclusive": "2026-12-01"}))
