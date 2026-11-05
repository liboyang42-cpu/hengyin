"""Theme by club lead 9003 with node bound to template draft 6005 (title holds the made-up library token).
Does the theme save/publish, and can another player read the template text?"""
import datetime, json
from common import *
wx_mode("pass")
p3 = tok("e2e-openid-9003"); p4 = tok("e2e-openid-9004")
D = datetime.datetime.now().replace(hour=9, minute=0, second=0, microsecond=0)
d = lambda k: (D + datetime.timedelta(days=k)).strftime("%Y-%m-%d %H:%M:%S")
st = http("POST", "/api/publisher/identity/status", token=p3, json_body={})
if not ((st.get("data") or {}).get("registered")):
    print("identity", http("POST", "/api/publisher/identity", token=p3, json_body={"realName": "测试主理人", "idCard": "110101199001010015", "consent": True, "source": "topic_publish"}))
dto = {"name": "j5 桥边主题", "subtitle": "j5", "description": "干净的简介", "startDate": d(10), "endDate": d(40), "saleEndAt": d(30),
       "imgUrl": "https://rigbucket.oss-rig.invalid/upload/205dc125ee594333b2e681429bfef410.png", "categoryIds": "1", "productType": 2, "publishMode": "normal", "tickets": [], "clubId": 7001, "recruitDeadline": d(5), "openMerchantPool": 1,
       "chapters": [{"name": "第1章", "description": "j5", "schemaVersion": 1, "required": 1, "recruitEnabled": 1, "categoryId": 4001, "termsMode": "PERK", "perkMinValue": 30, "maxMerchant": 1,
                     "blocks": [{"type": "text", "content": "j5"}, {"type": "node", "nodeIndex": 0}],
                     "nodes": [{"name": "桥头", "address": "上海市黄浦区", "longitude": "121.470000", "latitude": "31.230000", "nodeTime": 30, "templateId": 6005}]}]}
r = http("POST", "/api/topic/v2/create", token=p3, json_body=dto); print("create", json.dumps(r, ensure_ascii=False)[:300])
print(q("SELECT id,name,status,user_status,check_status FROM cms_topic WHERE name='j5 桥边主题'"))
print(q("SELECT n.id,n.topic_id,n.template_id FROM cms_topic_node n JOIN cms_topic t ON t.id=n.topic_id WHERE t.name='j5 桥边主题'"))
print(q("SELECT id,title,draft_status,status,publish_status,check_status FROM cms_member_template WHERE id=6005"))
print(q("SELECT id,biz_type,biz_id,status FROM audit_task ORDER BY id DESC LIMIT 3"))
for path, body in (("/api/template/info", {"id": 6005}), ("/api/template/detail", {"id": 6005})):
    print("player", path, json.dumps(http("POST", path, token=p4, json_body=body), ensure_ascii=False)[:300])
