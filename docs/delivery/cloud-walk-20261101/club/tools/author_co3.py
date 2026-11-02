import sys; sys.path.insert(0, '/root/sc')
from common import *
tok, me = login('walk-club-lead')
T = json.load(open('/root/sc/co_templates.json'))
S = [("外滩源壹号 · 原英国领事馆", "上海市黄浦区中山东一路33号", 121.48841, 31.24268),
     ("真光大楼", "上海市黄浦区圆明园路209号", 121.48712, 31.24305),
     ("光陆大楼", "上海市黄浦区虎丘路146号", 121.48660, 31.24140),
     ("乍浦路桥", "上海市虹口区乍浦路桥北堍", 121.48770, 31.24470),
     ("上海邮政大楼", "上海市虹口区北苏州路250号", 121.48380, 31.24490),
     ("四川路桥", "上海市虹口区四川北路桥南堍", 121.48500, 31.24440),
     ("外白渡桥", "上海市黄浦区中山东一路外白渡桥", 121.49000, 31.24350),
     ("黄浦公园 · 人民英雄纪念塔", "上海市黄浦区中山东一路500号", 121.49140, 31.24100)]
CH = [("第一章 迟到的电报", "1927 年冬，一封从伦敦发出的电报在外滩源的电报房里压了整整一百年。今天，你们是被临时叫来的信使。", [0,1,2]),
      ("第二章 苏州河上的信使", "电报的下半截被人带过了苏州河。顺着三座桥，找到当年那位信使留下的记号。", [3,4,5]),
      ("第三章 江边的回电", "所有线索都指向黄浦江边。把三章拿到的数字合在一起，替百年前的收报人发出回电。", [6,7])]
now = sh_now().replace(second=0, microsecond=0)
fmt = lambda d: d.strftime('%Y-%m-%d %H:%M:%S')
chapters = []
for name, story, idx in CH:
    nodes, blocks = [], [{"type": "text", "content": story}]
    for k, si in enumerate(idx):
        n, addr, lng, lat = S[si]
        nodes.append({"name": n, "address": addr, "longitude": "%.6f" % lng, "latitude": "%.6f" % lat, "nodeTime": 15, "templateId": T[si],
                      "description": "到达%s附近 50 米内开始答题。" % n})
        blocks.append({"type": "node", "nodeIndex": k})
    chapters.append({"name": name, "description": story, "schemaVersion": 1, "required": 1, "blocks": blocks, "nodes": nodes})
dto = {"name": "外滩源 · 迟到百年的电报（社员场）", "subtitle": "沿苏州河三座桥，替百年前的收报人发出回电",
       "description": "2 小时城市定向：外滩源出发，过乍浦路桥、四川路桥，在外白渡桥边收尾。3 章 8 站，文字题、选择题和拍照任务混合，适合 2–4 人一队。",
       "startDate": fmt(now), "endDate": fmt(now + datetime.timedelta(days=60)), "saleEndAt": fmt(now + datetime.timedelta(days=55)),
       "categoryIds": "1", "productType": 1, "publishMode": "normal", "clubId": 990004, "imgUrl": "", "teamMode": 1, "teamMaxMembers": 4,
       "chapters": chapters,
       "tickets": [{"name": "社员免费场", "price": 0, "totalStock": 20, "mode": 1, "teamSize": 4,
                    "meetingPoint": "外滩源壹号 正门台阶", "meetingPointAddress": "上海市黄浦区中山东一路33号",
                    "gatherLng": 121.48841, "gatherLat": 31.24268,
                    "startTime": fmt(now), "endTime": fmt(now + datetime.timedelta(days=60)),
                    "description": "领队全程带队，含 8 站讲解与回电纪念章。", "refundSupported": True}]}
r = cl.http("POST", "/api/topic/v2/create", token=tok, json_body=dto)
print(J(r))
