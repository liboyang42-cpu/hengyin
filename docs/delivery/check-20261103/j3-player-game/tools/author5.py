"""j3 setup (API leg): a short solo theme with legacy hint stations and a 尾声 ending chapter, authored by club lead 9003
through the same endpoints the editor posts to. First unpublishes 990016 (stuck at D20) to free an author slot."""
from common import *
tok, me = login('e2e-openid-9003')
#print('unpublish 990016', J(cl.http("POST", "/api/topic/update_user_status", token=tok, form={"id": "990016", "expectedUserStatus": "1"})))
T = [("门楣上的年份", {"validationMethod": 1, "questionName": "门楣上刻的年份是哪一年？（填数字）", "questionAnswer": "1873",
       "hint1": "抬头看正门拱券。", "hint2": "十九世纪七十年代。", "answerReveal": "1873，这座楼重建落成的那一年。", "feedbackText": "钥匙不在锁里，在数字里。"}),
     ("钟楼的方向", {"validationMethod": 3, "questionName": "从这里看，钟楼在哪个方向？", "questionA": "东", "questionB": "西", "questionC": "南", "questionD": "北",
       "correctAnswer": "A", "hint1": "江在东边。", "hint2": "看太阳升起的地方。", "answerReveal": "东边，紧挨着江。", "feedbackText": "老周的第二张字条在钟楼下。"})]
tids = [6063, 6064]
for title, extra in []:
    body = {"title": title, "description": extra["questionName"], "isSync": 0, "duration": 8, "difficulty": "普通", "players": "1人", "usageLocation": "户外"}
    body.update(extra)
    r = cl.http("POST", "/api/template/draft", token=tok, json_body=body)
    d = r.get('data'); tids.append(d if isinstance(d, int) else (d or {}).get('id') if isinstance(d, dict) else None)
    print('template', title, tids[-1], (r.get('msg') or '')[:60])
BASE = datetime.datetime.now().replace(hour=9, minute=0, second=0, microsecond=0)
dd = lambda off: (BASE + datetime.timedelta(days=off)).strftime("%Y-%m-%d %H:%M:%S")
nodes = [{"name": "外滩源·旧邮筒", "address": "上海市黄浦区圆明园路1号", "longitude": "121.490000", "latitude": "31.238000", "nodeTime": 20, "templateId": tids[0]},
         {"name": "海关大楼·钟楼", "address": "上海市黄浦区中山东一路13号", "longitude": "121.490400", "latitude": "31.238300", "nodeTime": 20, "templateId": tids[1]}]
chapters = [{"name": "序章", "opening": True, "schemaVersion": 1, "blocks": [{"type": "text", "content": "老周的字条只有一句：钟声响起之前，找到那两个数字。"}], "nodes": []},
            {"name": "第一章 钟声之前", "schemaVersion": 1, "required": 1, "blocks": [{"type": "text", "content": "沿着江边走，先去旧邮筒。"}, {"type": "node", "nodeIndex": 0}, {"type": "text", "content": "邮筒背面刻着一行小字。"}, {"type": "node", "nodeIndex": 1}], "nodes": nodes},
            {"name": "尾声", "schemaVersion": 1, "ending": {"fallback": True}, "blocks": [{"type": "text", "content": "钟声响了十二下。老周的字条到这里就齐了。"}], "nodes": []}]
look = sys.argv[1] if len(sys.argv) > 1 else ''
dto = {"name": "外滩钟声·尾声", "subtitle": "", "description": "两站，一个结局。", "startDate": dd(-1), "endDate": dd(30), "saleEndAt": dd(29),
       "categoryIds": "1", "imgUrl": "/profile/upload/2026/11/04/j3street.png", "productType": 1, "publishMode": "normal", "clubId": 7001,
       "selfPlay": 1, "selfPlayPrice": "6.60", "selfPlayQuota": 50, "chapters": chapters, "routeMode": "LINEAR",
       "tickets": [{"name": "场次票", "price": "19.90", "totalStock": 30, "mode": 1, "refundRule": "开场前24小时可退", "refundSupported": True,
                    "startTime": dd(-1), "endTime": dd(30), "saleStartTime": dd(-1), "saleEndTime": dd(29)}]}
if look: dto["look"] = look
r = cl.http("POST", "/api/topic/v2/create", token=tok, json_body=dto)
print('create', J(r)[:400])
