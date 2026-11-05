"""C (API leg, labelled): POST /api/topic/v2/create as the lead with the editor's own body shape
(fabu/index.js _buildSubmitPayload): city orienteering, club-owned, 商家承接 on, recruitDeadline set (the editor now
requires it), no saleEndAt (the editor never sends one). Cover: uploaded through the real /api/common/uploadOSS
(OSS stand-in). Reason for the API leg: the editor's 补内容 / 主题描述 input pop-ups are post-launch (owner), and
its native date wheels cannot be driven in the rig."""
from common import *
import subprocess
tok, me = login('j2b-lead'); mid = me['id']
T = state()['templates']
jpg = '/tmp/j2b-cover.jpg'
subprocess.run([os.path.expanduser('~/walk-rig/venv/bin/python'), '-c', "from PIL import Image,ImageDraw;i=Image.new('RGB',(900,1200),(40,70,120));d=ImageDraw.Draw(i);d.text((60,80),'fountain',fill='white');i.save('%s')" % jpg], check=True)
up = subprocess.run(['curl', '-s', '--noproxy', '*', '-H', 'Authorization: Bearer ' + tok, '-F', 'file=@' + jpg, 'http://127.0.0.1:8099/api/common/uploadOSS'], capture_output=True, text=True).stdout
print('upload', up[:300]); u = json.loads(up)
cover = u.get('url') or (u.get('data') or {}).get('url') if isinstance(u.get('data'), dict) else u.get('url')
print('cover', (cover or '')[:120])
S = [("人民广场音乐喷泉", "上海市黄浦区人民大道", 121.4752, 31.2317),
     ("上海博物馆", "上海市黄浦区人民大道201号", 121.4755, 31.2284),
     ("大世界", "上海市黄浦区西藏南路1号", 121.4810, 31.2297)]
CH = [("第一章 广场的回声", "傍晚的人民广场，喷泉开始报时。跟着水声找到第一条线索。", [0, 1]),
      ("第二章 大世界的灯", "线索指向东边的老游乐场。塔楼的灯一盏盏亮起来。", [2])]
now = sh_now()
day = now.strftime('%Y-%m-%d')
end = (now + datetime.timedelta(days=60)).strftime('%Y-%m-%d')
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
       "startDate": day + " 00:00:00", "endDate": end + " 23:59:59", "imgUrl": cover, "imgArr": "",
       "categoryIds": "1", "productType": 1, "publishMode": "pro", "clubId": 990004, "teamMode": 1, "teamMaxMembers": 4,
       "openMerchantPool": 1, "openClubPool": 0, "recruitDeadline": day + " 23:59:59", "selfPlay": 1, "selfPlayPrice": 19.90, "selfPlayQuota": 0,
       "routeMode": "LINEAR", "routeGraphJson": "", "publishToCreative": 0, "chapters": chapters,
       "tickets": [{"name": "领队场", "price": 39, "totalStock": 20, "mode": 1, "teamSize": 4,
                    "meetingPoint": "人民广场 1 号口", "meetingPointAddress": "上海市黄浦区人民大道",
                    "gatherLng": 121.4737, "gatherLat": 31.2304, "syncWithTheme": True,
                    "startTime": day + " 00:00:00", "endTime": end + " 23:59:59",
                    "description": "领队全程带队。", "refundSupported": True}]}
r = cl.http("POST", "/api/topic/v2/create", token=tok, json_body=dto)
print(J(r))
d = r.get('data'); tid = d if isinstance(d, int) else (d or {}).get('topicId') if isinstance(d, dict) else None
if tid: save(topic=tid)
print(cl.q("SELECT id,club_id,product_type,merchant_status,lifecycle,recruit_deadline,sale_end_at,merchant_sign_up_start_date,merchant_sign_up_end_date,img_url FROM cms_topic WHERE id=%s", (tid,)))
print(cl.q("SELECT id,name,template_id,chapter_id FROM cms_topic_node WHERE topic_id=%s ORDER BY id", (tid,)))
