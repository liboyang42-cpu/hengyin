"""Theme by club lead 9003 whose node is bound to a node-game draft. Usage: p_theme_bound.py <name> <templateId> [busy] [draft]
draft: save as draft (saveDraft=true) then publish through /api/topic/publish-draft (the editor's 发布草稿 path)."""
import sys, datetime, json
from common import *
name, tid = sys.argv[1], int(sys.argv[2]); busy = "busy" in sys.argv[3:]; draft = "draft" in sys.argv[3:]
import os
p3 = tok(os.environ.get("WHO", "e2e-openid-9003"))
D = datetime.datetime.now().replace(hour=9, minute=0, second=0, microsecond=0)
d = lambda k: (D + datetime.timedelta(days=k)).strftime("%Y-%m-%d %H:%M:%S")
st = http("POST", "/api/publisher/identity/status", token=p3, json_body={})
if not ((st.get("data") or {}).get("registered")):
    print("identity", http("POST", "/api/publisher/identity", token=p3, json_body={"realName": "测试主理人", "idCard": os.environ.get("IDCARD", "110101199001010015"), "consent": True, "source": "topic_publish"}))
dto = {"name": name, "subtitle": "j5", "description": "干净的简介", "startDate": d(10), "endDate": d(40), "saleEndAt": d(30),
       "imgUrl": "https://rigbucket.oss-rig.invalid/upload/205dc125ee594333b2e681429bfef410.png", "categoryIds": "1", "productType": 2, "publishMode": "normal", "tickets": [], "clubId": int(os.environ.get("CLUB", "7001")), "recruitDeadline": d(5), "openMerchantPool": 1,
       "chapters": [{"name": "第1章", "description": "j5", "schemaVersion": 1, "required": 1, "recruitEnabled": 1, "categoryId": 4001, "termsMode": "PERK", "perkMinValue": 30, "maxMerchant": 1,
                     "blocks": [{"type": "text", "content": "j5"}, {"type": "node", "nodeIndex": 0}],
                     "nodes": [{"name": "桥头", "address": "上海市黄浦区", "longitude": "121.470000", "latitude": "31.230000", "nodeTime": 30, "templateId": tid}]}]}
if draft:
    dto["saveDraft"] = 1
    wx_mode("pass")
    r = http("POST", "/api/topic/v2/create", token=p3, json_body=dto); print("save draft", json.dumps(r, ensure_ascii=False)[:300])
    cid = (r.get("data") or {}).get("topicId") if isinstance(r.get("data"), dict) else r.get("data")
    import os
    if os.environ.get("LATE_WORD"): print("import", http("POST", "/business/sensitive/words/import", token=admin_token(), json_body={"text": "%s,OTHER,BLOCK" % os.environ["LATE_WORD"]}).get("data"))
    if busy: wx_mode("busy")
    r = http("POST", "/api/topic/publish-draft?id=%s" % cid, token=p3, json_body={}); print("publish-draft", json.dumps(r, ensure_ascii=False)[:400])
else:
    if busy: wx_mode("busy")
    r = http("POST", "/api/topic/v2/create", token=p3, json_body=dto); print("create", json.dumps(r, ensure_ascii=False)[:400])
wx_mode("pass")
print(q("SELECT id,name,status,user_status,check_status,is_draft FROM cms_topic WHERE name=%s", (name,)))
