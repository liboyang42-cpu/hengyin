"""FIXTURE (API leg, labelled): merchant 9002 publishes its own paid activity through the real /api/activity/publish,
same body the 发活动 form sends (the form's map/date wheels don't run in the rig, j1 B1). Cover = APPROVED media-asset
fixture (as chain_platform.seed_approved_cover does)."""
exec(open('/root/p2/fx_common.py').read())
import datetime
tok, me = login('e2e-openid-9002'); mid = me['id']
key = "upload/p2-activity-cover-%d.jpg" % mid
cl.ex("INSERT INTO app_media_asset(member_id,object_key,media_type,review_state,version,create_time,update_time) SELECT %s,%s,'IMAGE','APPROVED',0,NOW(),NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM app_media_asset WHERE member_id=%s AND object_key=%s)", (mid, key, mid, key))
cover = "media-asset:%d" % cl.q("SELECT id FROM app_media_asset WHERE member_id=%s AND object_key=%s", (mid, key))[0][0]
now = datetime.datetime.utcnow() + datetime.timedelta(hours=8)
f = lambda d: d.strftime('%Y-%m-%d %H:%M:%S')
s, e = now + datetime.timedelta(days=3), now + datetime.timedelta(days=3, hours=3)
body = {"name": "周六手冲咖啡体验", "addressName": "探店日样板咖啡", "address": "上海市黄浦区咖啡", "longitude": "121.4737", "latitude": "31.2304",
        "description": "一小时手冲体验，含两杯咖啡。", "startDate": f(s), "endDate": f(e), "categoryIds": "1", "templateId": 6001, "imgUrl": cover,
        "collaborators": [], "scope": "MERCHANT", "requestId": "p2-activity-1",
        "tickets": [{"name": "单人体验", "price": 39, "totalStock": 10, "description": "", "startTime": f(s), "endTime": f(e), "refundSupported": True}]}
def idcard(b):
    w=[7,9,10,5,8,4,2,1,6,3,7,9,10,5,8,4,2]; c='10X98765432'; return b + c[sum(int(a)*x for a,x in zip(b,w))%11]
print('identity', json.dumps(cl.http("POST", "/api/publisher/identity", token=tok, json_body={"realName": "测试店主", "idCard": idcard("11010119900307123"), "consent": True, "source": "topic_publish"}), ensure_ascii=False)[:200])
r = cl.http("POST", "/api/activity/publish", token=tok, json_body=body); print(json.dumps(r, ensure_ascii=False)[:500])
print(cl.q("SELECT id,name,member_id,status,start_date FROM cms_activity ORDER BY id DESC LIMIT 1"))
