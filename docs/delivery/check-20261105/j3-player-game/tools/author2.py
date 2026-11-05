"""j3 second pass setup (API leg, labelled): a club lead authors a solo story theme through the endpoints the pro editor
posts to (/api/template/draft, /api/topic/v2/create). Game configs: gencfg2.js (editor's own functions).
Usage: author2.py <openid> <clubId> <name> <keys,comma> <look-json or ''> <rules: ''|state|d20|state+d20> <stateKey>"""
from common import *
import subprocess, time
openid, club, name, keys, look, rmode, skey = sys.argv[1:8]
tok, me = login(openid); print('author', me.get('id'))
print('become-leader', J(cl.http("POST", "/api/club/become-leader", token=tok, json_body={"leaderName": "林小舟", "phone": "13900000008", "experience": "1-3年", "identity": "城市漫步爱好者"}))[:120])
st = cl.http("POST", "/api/publisher/identity/status", token=tok, json_body={})
if not (st.get('data') or {}).get('registered'):
    print('identity', J(cl.http("POST", "/api/publisher/identity", token=tok, json_body={"realName": "周晓岚", "idCard": idcard('31010419880512003'), "consent": True, "source": "topic_publish"})))
VMS = {k: (int(k.split('@')[1]) if '@' in k else int(os.environ.get('VM', '0'))) for k in keys.split(',')}
keys_raw = keys
keys = ','.join(k.split('@')[0] for k in keys.split(','))
cfg = json.loads(subprocess.run(['node', '/root/j3t/gencfg2.js', '/profile/upload/2026/11/05/j3cat.png', keys], capture_output=True, text=True, check=True).stdout)
klist = keys_raw.split(',')
prev = (state().get(skey) or {}).get('templates') or {}
tids = []
for k in klist:
    if prev.get(k): tids.append(prev[k]); continue
    v = cfg[k.split('@')[0]]
    if v['err']: print('CFGERR', k, v['err']); tids.append(None); continue
    time.sleep(3)
    body = {"title": v['label'] + '·' + name[:6], "description": v['label'], "validationMethod": VMS[k], "isSync": 0, "advancedConfigJson": v['json']}
    r = cl.http("POST", "/api/template/draft", token=tok, json_body=body)
    d = r.get('data'); tid = d if isinstance(d, int) else (d or {}).get('id') if isinstance(d, dict) else None
    print('template', k, tid, (r.get('msg') or '')[:120]); tids.append(tid)
save(**{skey: {"templates": dict(zip(klist, tids)), "author": openid}})
# journeyRules as the editor writes them: 状态 (story-initial-state saveInitialState) and d20 检定 (theme-d20 setCheckEnabled)
rules = None
if rmode:
    node = r"""const P='/home/user/j3/chengyinhub-xcx/pages/publish/';const s=require(P+'utils/publish/story-initial-state.js');const d=require(P+'fabu/theme-d20.js');
let raw='';const m=process.argv[1];
if(m.includes('state')){const r=s.parse(raw);r.hp.enabled=true;r.stateEnabled=true;raw=s.serializeChanges(raw,r);}
if(m.includes('d20')){raw=s.serializeChanges(raw,d.setCheckEnabled(raw,true));}
console.log(raw);"""
    rules = subprocess.run(['node', '-e', node, rmode], capture_output=True, text=True, check=True).stdout.strip()
    print('journeyRules', rules)
BASE = sh_now().replace(hour=9, minute=0, second=0, microsecond=0)
dd = lambda off: (BASE + datetime.timedelta(days=off)).strftime("%Y-%m-%d %H:%M:%S")
groups = [klist[i:i+6] for i in range(0, len(klist), 6)]
chapters = [{"name": "序章", "opening": True, "schemaVersion": 1, "blocks": [{"type": "text", "content": "雨停了。老周留下一张字条：去外滩，钟声会告诉你下一步。"}], "nodes": []}]
n = 0
for gi, g in enumerate(groups):
    nodes, blocks = [], [{"type": "text", "content": "第%d章开始了。沿着江边往北走。" % (gi + 1)}]
    for k in g:
        t = tids[klist.index(k)]
        if not t: continue
        nodes.append({"name": "%02d %s" % (n + 1, cfg[k.split('@')[0]]['label']), "address": "上海市黄浦区中山东一路%d号" % (n + 1),
                      "longitude": "%.6f" % (121.4900 + n * 0.0004), "latitude": "%.6f" % (31.2380 + n * 0.0003),
                      "nodeTime": 10, "templateId": t})
        blocks.append({"type": "node", "nodeIndex": len(nodes) - 1}); n += 1
    chapters.append({"name": "第%s章 江边%d" % ("一二三四五六七"[gi], gi + 1), "schemaVersion": 1, "required": 1, "blocks": blocks, "nodes": nodes})
chapters.append({"name": "尾声", "schemaVersion": 1, "ending": {"fallback": True}, "blocks": [{"type": "text", "content": "钟声响了十二下。老周的字条到这里就齐了。"}], "nodes": []})
dto = {"name": name, "subtitle": "", "description": "一个人的外滩夜游。", "startDate": dd(-1), "endDate": dd(30), "saleEndAt": dd(29),
       "categoryIds": "1", "imgUrl": "/profile/upload/2026/11/05/j3street.png", "productType": 1, "publishMode": "normal", "clubId": int(club),
       "selfPlay": 1, "selfPlayPrice": "9.90", "selfPlayQuota": 50, "chapters": chapters, "routeMode": "LINEAR",
       "tickets": [{"name": "场次票", "price": "19.90", "totalStock": 30, "mode": 1, "refundRule": "开场前24小时可退", "refundSupported": True,
                    "startTime": dd(-1), "endTime": dd(30), "saleStartTime": dd(-1), "saleEndTime": dd(29)}]}
if look: dto["look"] = look
if rules: dto["journeyRules"] = rules
r = cl.http("POST", "/api/topic/v2/create", token=tok, json_body=dto)
print('create', J(r)[:700])
d = r.get('data'); tid = d if isinstance(d, int) else ((d or {}).get('topicId') or (d or {}).get('id'))
save(**{skey: {"topic": tid, "templates": dict(zip(klist, tids)), "author": openid}})
