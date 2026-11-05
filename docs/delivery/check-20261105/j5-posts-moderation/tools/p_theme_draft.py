"""Save a clean theme draft for club lead 9003 (node bound to node game <templateId>) so the editor can publish it.
Usage: p_theme_draft.py <name> <templateId>"""
import sys, datetime, json
from common import *
name, tid = sys.argv[1], int(sys.argv[2]); wx_mode("pass")
p3 = tok("e2e-openid-9003")
D = datetime.datetime.now().replace(hour=9, minute=0, second=0, microsecond=0)
d = lambda k: (D + datetime.timedelta(days=k)).strftime("%Y-%m-%d %H:%M:%S")
dto = {"name": name, "subtitle": "j5", "description": "干净的简介", "startDate": d(10), "endDate": d(40), "saleEndAt": d(30), "saveDraft": 1,
       "imgUrl": "https://oss-rig.invalid/upload/205dc125ee594333b2e681429bfef410.png", "categoryIds": "1", "productType": 2, "publishMode": "normal", "tickets": [], "clubId": 7001, "recruitDeadline": d(5), "openMerchantPool": 1,
       "chapters": [{"name": "第1章", "description": "j5", "schemaVersion": 1, "required": 1, "recruitEnabled": 1, "categoryId": 4001, "termsMode": "PERK", "perkMinValue": 30, "maxMerchant": 1,
                     "blocks": [{"type": "text", "content": "j5"}, {"type": "node", "nodeIndex": 0}],
                     "nodes": [{"name": "桥头", "address": "上海市黄浦区", "longitude": "121.470000", "latitude": "31.230000", "nodeTime": 30, "templateId": tid}]}]}
r = http("POST", "/api/topic/v2/create", token=p3, json_body=dto); print("save draft", json.dumps(r, ensure_ascii=False)[:300])
