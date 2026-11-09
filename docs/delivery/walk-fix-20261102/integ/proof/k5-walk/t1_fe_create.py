from common import *
st = state(); tok, _ = login(st['lead_openid']); atok = admin()
now = datetime.datetime.utcnow() + datetime.timedelta(hours=8)
day = now.strftime('%Y-%m-%d'); end = (now + datetime.timedelta(days=60)).strftime('%Y-%m-%d')
ch = [{"name": "第%d章 %s" % (i+1, n), "description": "进店完成一次体验", "schemaVersion": 1, "required": 1, "recruitEnabled": 1,
       "categoryId": 4001, "termsMode": "PERK", "perkMinValue": 30, "maxMerchant": 1,
       "blocks": [{"type": "text", "content": "本章一店(%s)" % n}], "nodes": []} for i, n in enumerate(["咖啡", "书店"])]
dto = {"name": "外滩探店日", "subtitle": "", "description": "两家店。", "startDate": day + " 00:00:00", "endDate": end + " 23:59:59",
       "imgUrl": st['cover'], "imgArr": "", "categoryIds": "1", "productType": 2, "publishMode": "pro",
       "clubId": st['club'], "openMerchantPool": 1, "openClubPool": 0, "recruitDeadline": day + " 23:59:59", "selfPlay": 0,
       "chapters": ch, "tickets": [{"name": "门票", "price": 29, "totalStock": 20, "mode": 1, "teamSize": 4, "syncWithTheme": True,
        "startTime": day + " 00:00:00", "endTime": end + " 23:59:59", "refundSupported": True}]}
r = http('FE topic v2/create (zero nodes)', 'POST', '/api/topic/v2/create', token=tok, json_body=dto)
