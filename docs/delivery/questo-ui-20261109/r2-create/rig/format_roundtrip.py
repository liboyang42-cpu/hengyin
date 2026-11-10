#!/usr/bin/env python3
"""Rig-only (r2-create): prove paragraph style + inline marks survive the real write path.

Publisher 9003 creates a 城市定向 theme through the real POST /api/topic/v2/create with a story chapter whose
text blocks carry `style` (h2 / quote) and `marks` (bold / strike), then:
  1. reads back cms_topic_chapter.blocks and description from MySQL;
  2. sends a block with an invalid style and an out-of-range mark and shows the server refusal.
Usage: CAND=<candidate on this branch> PATH=$RIG_HOME/work/bin:$PATH $RIG_HOME/venv/bin/python format_roundtrip.py
"""
import datetime, json, os, pathlib, sys
for p in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy", "ALL_PROXY", "all_proxy"):
    os.environ.pop(p, None)
CAND = pathlib.Path(os.environ["CAND"])
sys.path.insert(0, str(CAND / "scripts" / "e2e"))
from chain_lib import http, q, ex, admin_token, app_token, readback  # noqa: E402
from chain_platform import seed_approved_cover  # noqa: E402

NOW = datetime.datetime.now().replace(microsecond=0)
d = lambda days: (NOW + datetime.timedelta(days=days)).strftime("%Y-%m-%d %H:%M:%S")
stamp = NOW.strftime("%m%d%H%M%S")
ptok, _ = app_token("13900000003")
status = http("POST", "/api/publisher/identity/status", token=ptok)
if not ((status.get("data") or {}).get("registered")):
    http("POST", "/api/publisher/identity", token=ptok, json_body={
        "realName": "测试主理人", "idCard": "110101199001010015", "consent": True, "source": "topic_publish"})
tpl = http("POST", "/api/template/publish", token=ptok, json_body={
    "title": "rig 格式往返 %s" % stamp, "validationMethod": 0, "requestId": "r2c-fmt-%s" % stamp})
tpl_id = tpl.get("data") if isinstance(tpl.get("data"), int) else (tpl.get("data") or {}).get("id")
print("template", tpl_id)
atok = admin_token()
for task_id, version in q("SELECT id, version FROM audit_task WHERE biz_id=%s AND status=0 AND biz_type LIKE %s", (tpl_id, "%TEMPLATE%")):
    http("POST", "/business/audit/review", token=atok, json_body={
        "taskId": task_id, "status": 1, "expectedVersion": version, "reasonCode": "RIG", "reasonText": "rig 放行"})


def chapter(blocks):
    return [{"name": "雨后的咖啡馆", "schemaVersion": 1, "required": 1, "recruitEnabled": 0, "blocks": blocks,
             "nodes": [{"name": "人民广场站", "address": "上海市黄浦区人民广场站", "longitude": "121.473700",
                        "latitude": "31.230400", "nodeTime": 20, "sortID": 1, "clientNodeKey": "st1", "templateId": tpl_id}]}]


def create(blocks, name):
    dto = {"name": name, "subtitle": "rig", "description": "rig 段落样式往返", "startDate": d(-1), "endDate": d(60),
           "saleEndAt": d(45), "categoryIds": "1", "productType": 1, "publishMode": "normal", "clubId": 7001,
           "chapters": chapter(blocks), "tickets": [], "imgUrl": seed_approved_cover(ex, q, 9003)}
    return http("POST", "/api/topic/v2/create", token=ptok, json_body=dto)


good = [
    {"type": "text", "key": "h", "content": "一 · 雨停之后", "style": "h2"},
    {"type": "text", "key": "p", "content": "她推开咖啡馆的门，铃铛响了一声。",
     "marks": [{"from": 9, "to": 15, "type": "bold"}, {"from": 0, "to": 3, "type": "strike"}]},
    {"type": "text", "key": "q", "content": "杯底压着一张纸条。", "style": "quote"},
    {"type": "node", "key": "n", "nodeIndex": 0},
]
r = create(good, "格式往返 %s" % stamp)
print("create", json.dumps(r, ensure_ascii=False)[:200])
data = r.get("data")
tid = data if isinstance(data, int) else ((data or {}).get("topicId") or (data or {}).get("id"))
if tid:
    rows = q("SELECT id, description, blocks FROM cms_topic_chapter WHERE topic_id=%s", (tid,))
    for cid, desc, blocks in rows:
        print("[库回读] chapter %s description=%r" % (cid, desc))
        for b in json.loads(blocks):
            print("   ", json.dumps(b, ensure_ascii=False))

bad = [dict(good[0], style="h3")] + good[1:]
print("bad style ->", json.dumps(create(bad, "格式往返坏样式 %s" % stamp), ensure_ascii=False)[:200])
bad2 = [good[0], dict(good[1], marks=[{"from": 10, "to": 99, "type": "bold"}])] + good[2:]
print("bad mark  ->", json.dumps(create(bad2, "格式往返坏区间 %s" % stamp), ensure_ascii=False)[:200])
