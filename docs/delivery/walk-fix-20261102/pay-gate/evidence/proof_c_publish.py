import sys, os
sys.argv=[sys.argv[0]]
exec(open('/home/user/hengyin/docs/delivery/walk-fix-20261102/pay-gate/proof_http.py').read().replace('if __name__ == "__main__":','if False:'))
CLUB_OWNER_PHONE, CLUB_ID = "13900000008", 7002
otok,_ = app_token(CLUB_OWNER_PHONE)
step("C 俱乐部自办收费独立活动:主理人 9008 发布(俱乐部 7002,票价 39)")
d = lambda days, h: q("SELECT DATE_FORMAT(DATE_ADD(NOW(), INTERVAL 8 HOUR) + INTERVAL %s DAY, '%%Y-%%m-%%d " + h + "')", (days,))[0][0]
start, end, sale_end = d(9, "10:00:00"), d(9, "12:00:00"), d(9, "09:00:00")
now = q("SELECT DATE_FORMAT(DATE_ADD(NOW(), INTERVAL 8 HOUR), '%%Y-%%m-%%d %%H:%%i:%%s')", ())[0][0]
body = {"name": "pay-gate 俱乐部自办收费场", "description": "rig proof", "imgUrl": "/profile/rig.jpg",
        "addressName": "人民广场", "longitude": "121.4737", "latitude": "31.2304", "address": "人民大道",
        "startDate": start, "endDate": end, "clubId": CLUB_ID,
        "tickets": [{"name": "成人票", "price": 39, "totalStock": 20, "startTime": start, "endTime": end, "description": ""}]}
pub = http("POST", "/api/activity/publish", token=otok, json_body=body)
show("publish", j(pub, 400))
if pub.get("code") == 500 and "实名" in str(pub.get("msg")):
    show("identity", j(http("GET", "/api/publisher/identity/status", token=otok)))
act = pub.get("data") if isinstance(pub.get("data"), int) else (pub.get("data") or {}).get("id") if isinstance(pub.get("data"), dict) else None
if not act:
    r = q("SELECT id FROM cms_activity WHERE name=%s ORDER BY id DESC LIMIT 1", ("pay-gate 俱乐部自办收费场",))
    act = r[0][0] if r else None
readback("cms_activity", "SELECT id,member_id,club_id,topic_id,status,publish_status FROM cms_activity WHERE id=%s", (act,))
readback("oms_ticket", "SELECT id,price,remaining_inventory FROM oms_ticket WHERE owner_type=2 AND owner_id=%s", (act,))
print("ACT", act)
