"""Probe (API leg): which editor-made game templates can a theme with 状态 (hp, as the editor's 状态 sheet writes) publish?
One-station theme per template, real /api/topic/v2/create. Refused creates write nothing."""
from common import *
import subprocess
tok, me = login(sys.argv[1]); club = int(sys.argv[2]); rmode = sys.argv[3]
st = state(); tpl = {}
for k in sys.argv[4].split(','):
    for g, t in ((st.get(k) or {}).get('templates') or {}).items():
        if t and g not in tpl: tpl[g] = t
node = r"""const P='/home/user/j3/chengyinhub-xcx/pages/publish/';const s=require(P+'utils/publish/story-initial-state.js');const d=require(P+'fabu/theme-d20.js');
let raw='';const m=process.argv[1];
if(m.includes('state')){const r=s.parse(raw);r.hp.enabled=true;r.stateEnabled=true;raw=s.serializeChanges(raw,r);}
if(m.includes('d20')){raw=s.serializeChanges(raw,d.setCheckEnabled(raw,true));}
console.log(raw);"""
rules = subprocess.run(['node', '-e', node, rmode], capture_output=True, text=True, check=True).stdout.strip()
BASE = sh_now().replace(hour=9, minute=0, second=0, microsecond=0)
dd = lambda off: (BASE + datetime.timedelta(days=off)).strftime("%Y-%m-%d %H:%M:%S")
for g, t in tpl.items():
    if sys.argv[5:] and g not in sys.argv[5:]: continue
    ch = [{"name": "第一章", "schemaVersion": 1, "required": 1, "blocks": [{"type": "node", "nodeIndex": 0}],
           "nodes": [{"name": "探针", "address": "上海市黄浦区中山东一路1号", "longitude": "121.490000", "latitude": "31.238000", "nodeTime": 10, "templateId": t}]}]
    dto = {"name": "探针·" + g, "description": "探针", "startDate": dd(-1), "endDate": dd(30), "saleEndAt": dd(29), "categoryIds": "1",
           "imgUrl": "/profile/upload/2026/11/05/j3street.png", "productType": 1, "publishMode": "normal", "clubId": club, "selfPlay": 1,
           "selfPlayPrice": "9.90", "selfPlayQuota": 50, "chapters": ch, "routeMode": "LINEAR", "journeyRules": rules,
           "tickets": [{"name": "场次票", "price": "19.90", "totalStock": 30, "mode": 1, "refundSupported": True, "startTime": dd(-1), "endTime": dd(30), "saleStartTime": dd(-1), "saleEndTime": dd(29)}]}
    r = cl.http("POST", "/api/topic/v2/create", token=tok, json_body=dto)
    d = r.get('data') or {}
    print('PROBE', rmode, g, r.get('code'), (r.get('msg') or '')[:160], d.get('topicId') if isinstance(d, dict) else '')
