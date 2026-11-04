"""C (API leg, labelled): POST /api/topic/v2/create as the lead, the same DTO the editor sends.
Cover: an APPROVED media asset fixture, exactly as the candidate's chain_platform.seed_approved_cover does (no OSS in the rig)."""
from common import *
tok, me = login('j2-lead-a'); mid = me['id']
T = state()['templates']
key = "upload/j2-topic-cover-%d.jpg" % mid
cl.ex("INSERT INTO app_media_asset(member_id,object_key,media_type,review_state,version,create_time,update_time) "
      "SELECT %s,%s,'IMAGE','APPROVED',0,NOW(),NOW() FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM app_media_asset WHERE member_id=%s AND object_key=%s)", (mid, key, mid, key))
cover = "media-asset:%d" % cl.q("SELECT id FROM app_media_asset WHERE member_id=%s AND object_key=%s", (mid, key))[0][0]
print('cover fixture', cover)
S = [("人民广场音乐喷泉", "上海市黄浦区人民大道", 121.4752, 31.2317),
     ("上海博物馆", "上海市黄浦区人民大道201号", 121.4755, 31.2284),
     ("大世界", "上海市黄浦区西藏南路1号", 121.4810, 31.2297)]
CH = [("第一章 广场的回声", "傍晚的人民广场，喷泉开始报时。跟着水声找到第一条线索。", [0, 1]),
      ("第二章 大世界的灯", "线索指向东边的老游乐场。塔楼的灯一盏盏亮起来。", [2])]
now = sh_now().replace(second=0, microsecond=0)
fmt = lambda d: d.strftime('%Y-%m-%d %H:%M:%S')
chapters = []
for name, story, idx in CH:
    nodes, blocks = [], [{"type": "text", "content": story}]
    for k, si in enumerate(idx):
        n, addr, lng, lat = S[si]
        nodes.append({"name": n, "address": addr, "longitude": "%.6f" % lng, "latitude": "%.6f" % lat, "nodeTime": 15, "templateId": T[si],
                      "description": "到达%s附近开始答题。" % n})
        blocks.append({"type": "node", "nodeIndex": k})
    chapters.append({"name": name, "description": story, "schemaVersion": 1, "required": 1, "blocks": blocks, "nodes": nodes})
dto = {"name": "人民广场 · 喷泉报时", "subtitle": "", "description": "90 分钟城市定向：人民广场出发，经上海博物馆，在大世界收尾。2 章 3 站，适合 2–4 人一队。",
       "startDate": fmt(now), "endDate": fmt(now + datetime.timedelta(days=60)), "saleEndAt": fmt(now + datetime.timedelta(days=55)),
       "categoryIds": "1", "productType": 1, "publishMode": "pro", "clubId": 990004, "imgUrl": cover, "teamMode": 1, "teamMaxMembers": 4,
       "openMerchantPool": 1, "selfPlay": 1, "selfPlayPrice": 19.90,
       "chapters": chapters,
       "tickets": [{"name": "领队场", "price": 39, "totalStock": 20, "mode": 1, "teamSize": 4,
                    "meetingPoint": "人民广场 1 号口", "meetingPointAddress": "上海市黄浦区人民大道",
                    "gatherLng": 121.4737, "gatherLat": 31.2304,
                    "startTime": fmt(now), "endTime": fmt(now + datetime.timedelta(days=60)),
                    "description": "领队全程带队。", "refundSupported": True}]}
r = cl.http("POST", "/api/topic/v2/create", token=tok, json_body=dto)
print(J(r))
d = r.get('data'); tid = d if isinstance(d, int) else (d or {}).get('id') if isinstance(d, dict) else None
if tid: save(topic=tid)
