"""M (API leg, labelled): club 自由探索 theme created by the club's current owner (林一帆, 990006) through /api/topic/v2/create
with the editor's body shape for productType 2 (as chain_platform.py builds it): 2 recruiting chapters (PERK, 1 shop each),
recruitDeadline today, no ticket price (priced at 确认终价). Reason for the API leg: editor input pop-ups are post-launch."""
from common import *
import subprocess
tok, me = login('j2b-m1'); mid = me['id']; print('owner', mid)
st = cl.http("POST", "/api/publisher/identity/status", token=tok, json_body={})
if not (st.get('data') or {}).get('registered'):
    print('identity', J(cl.http("POST", "/api/publisher/identity", token=tok, json_body={"realName": "林一帆", "idCard": idcard('31010419920511003'), "consent": True, "source": "topic_publish"})))
up = subprocess.run(['curl', '-s', '--noproxy', '*', '-H', 'Authorization: Bearer ' + tok, '-F', 'file=@/tmp/j2b-cover.jpg', 'http://127.0.0.1:8099/api/common/uploadOSS'], capture_output=True, text=True).stdout
cover = json.loads(up).get('url'); print('cover', bool(cover))
day = sh_now().strftime('%Y-%m-%d'); end = (sh_now() + datetime.timedelta(days=30)).strftime('%Y-%m-%d')
chapters = []
for i, (n, addr, lng, lat) in enumerate([("咖啡", "上海市黄浦区人民大道120号", 121.4745, 31.2312), ("书店", "上海市黄浦区福州路465号", 121.4790, 31.2350)]):
    chapters.append({"name": "第%d章 %s" % (i + 1, n), "description": "进店完成一次到店体验", "schemaVersion": 1, "required": 1,
                     "recruitEnabled": 1, "categoryId": 4001, "termsMode": "PERK", "perkMinValue": 30, "maxMerchant": 1,
                     "blocks": [{"type": "text", "content": "进店完成一次到店体验"}, {"type": "node", "nodeIndex": 0}],
                     "nodes": [{"name": "%s打卡点" % n, "address": addr, "longitude": "%.6f" % lng, "latitude": "%.6f" % lat, "nodeTime": 30}]})
dto = {"name": "人民广场 · 慢逛一下午", "subtitle": "", "description": "自由探索：两家店，任意顺序。",
       "startDate": day + " 00:00:00", "endDate": end + " 23:59:59", "imgUrl": cover, "imgArr": "", "categoryIds": "1",
       "productType": 2, "publishMode": "pro", "clubId": 990004, "openMerchantPool": 1, "openClubPool": 0,
       "recruitDeadline": day + " 23:59:59", "selfPlay": 0, "selfPlayPrice": 0, "teamMode": 0, "teamMaxMembers": 4,
       "routeMode": "LINEAR", "routeGraphJson": "", "chapters": chapters, "tickets": []}
r = cl.http("POST", "/api/topic/v2/create", token=tok, json_body=dto); print(J(r))
d = r.get('data'); tid = (d or {}).get('topicId') if isinstance(d, dict) else d
save(fe_topic=tid)
print(cl.q("SELECT id,club_id,product_type,lifecycle,recruit_deadline,merchant_sign_up_start_date,merchant_sign_up_end_date FROM cms_topic WHERE id=%s", (tid,)))
print(cl.q("SELECT id,name,recruit_enabled FROM cms_topic_chapter WHERE topic_id=%s", (tid,)))
