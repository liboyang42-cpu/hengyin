"""Activity publish by merchant 9002 with the client's payload shape (pages/publish/activity/index.js:524-550):
clean name/description, library token only in ticket name/description and the venue name. Read back."""
import datetime, json
from common import *
wx_mode("pass")
import os
m = tok(os.environ.get("WHO", "e2e-openid-9002"))
D = datetime.datetime.now().replace(minute=0, second=0, microsecond=0)
d = lambda k: (D + datetime.timedelta(days=k)).strftime("%Y-%m-%d %H:%M:%S")
cats = q("SELECT id FROM cms_activity_category LIMIT 1") if q("SHOW TABLES LIKE 'cms_activity_category'") else ()
body = {"name": "j5 周末咖啡品鉴", "description": "干净的活动说明",
        "imgUrl": "", "imgArr": "", "categoryIds": str(cats[0][0]) if cats else "1",
        "addressName": "zqvxlm 咖啡角", "longitude": "121.470000", "latitude": "31.230000", "address": "上海市黄浦区",
        "startDate": d(3), "endDate": d(4), "templateId": 0, "collaborators": [],
        "tickets": [{"name": "zqvxlm 体验票", "price": 0, "totalStock": 10, "description": "到店说 zqvxlm 领取", "startTime": d(3), "endTime": d(4)}]}
if os.environ.get("SCOPE"): body["scope"] = os.environ["SCOPE"]
r = http("POST", "/api/activity/publish", token=m, json_body=body)
print("publish", json.dumps(r, ensure_ascii=False)[:400])
aid = q("SELECT id,name,address_name,status,publish_status,check_status FROM cms_activity WHERE name='j5 周末咖啡品鉴' ORDER BY id DESC LIMIT 1")
print("activity", aid)
if aid:
    print("tickets", q("SELECT id,name,description FROM cms_activity_ticket WHERE activity_id=%s", (aid[0][0],)))
