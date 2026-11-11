#!/usr/bin/env python3
"""Rig-only fixture (qx-s0 staging; f-play adds SKIP_BUY=1 so the player buys the pass in the browser): a sellable self-run 城市定向 theme with one free self-play ticket.

Why: the rig's own sale_close chain stops on this base (「自由探索只开放内容所属俱乐部的场次」, then
「四店固定补偿产品已停止新开售」), so no B lane could reach a ticketed play state.

Path (real endpoints unless marked SQL):
  1. SQL fixture  approved cover media asset (same helper as chain_platform.seed_approved_cover)
  2. HTTP 9003    POST /api/topic/v2/create  productType=1 (城市定向, ordered nodes), club 7001,
                  no recruit / merchant pool / priced ticket => non-commercial, lifecycle stays DRAFT(0)
  3. HTTP admin   POST /business/audit/review for any pending task of this topic (content review)
  4. SQL fixture  cms_topic.self_play=1, self_play_price=0  <-- the only gap: create/update force self_play=0
                  unless selfPlayPrice>0, so no endpoint can save a ¥0 self-play pass
  5. HTTP player  POST /api/compliance/consents (activity_host_data_sharing)
  6. HTTP player  POST /api/user/address/action (participant name = realName)
  7. HTTP player  POST /api/registration/quote {ownerType:1}  -> payAmount 0
  8. HTTP player  POST /api/registration/create {ownerType:1} -> zero-amount completion (no payment callback)
  9. HTTP player  GET  /api/play/nodes?topicId=   -> 200 (was 402 「请先购买自玩通行证」)

Usage: CAND=<candidate worktree> PATH=$RIG_HOME/work/bin:$PATH $RIG_HOME/venv/bin/python free_orient_theme.py [player_openid_phone]
Prints TOPIC_ID=<id>. Idempotent per run: each run creates a new topic with a timestamped name.
"""
import datetime
import json
import os
import pathlib
import sys
import time

CAND = pathlib.Path(os.environ.get("CAND", str(pathlib.Path.home() / "walk-rig" / "candidate")))
for proxy in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy", "ALL_PROXY", "all_proxy"):
    os.environ.pop(proxy, None)
sys.path.insert(0, str(CAND / "scripts" / "e2e"))
from chain_lib import http, q, ex, admin_token, app_token, step, show, readback  # noqa: E402
from chain_platform import seed_approved_cover  # noqa: E402

PUBLISHER_PHONE, PUBLISHER_MEMBER, CLUB_ID = "13900000003", 9003, 7001
PLAYER_PHONE = sys.argv[1] if len(sys.argv) > 1 else "13900000004"
PLAYER_NAME = "E2E 玩家"
NOW = datetime.datetime.now().replace(microsecond=0)


def d(days):
    return (NOW + datetime.timedelta(days=days)).strftime("%Y-%m-%d %H:%M:%S")


def die(msg, resp=None):
    print("   ✗ " + msg + ("" if resp is None else " :: " + json.dumps(resp, ensure_ascii=False)[:400]))
    sys.exit(1)


def review_pending(atok, biz_id, like="%"):
    """Approve every pending audit task of biz_id via the real admin review endpoint,
    sending the task's current version as expectedVersion (what the admin page sends)."""
    for task_id, biz_type, version in q("SELECT id,biz_type,version FROM audit_task WHERE biz_id=%s AND status=0 "
                                        "AND biz_type LIKE %s ORDER BY id", (biz_id, like)):
        rr = http("POST", "/business/audit/review", token=atok, json_body={
            "taskId": task_id, "status": 1, "expectedVersion": version,
            "reasonCode": "RIG", "reasonText": "rig 放行"})
        show("review %s(%s) v%s" % (task_id, biz_type, version), (rr.get("msg") or "")[:60])


def main():
    atok = admin_token()
    ptok, _ = app_token(PUBLISHER_PHONE)
    if not (atok and ptok):
        die("登录失败")
    stamp = time.strftime("%m%d%H%M%S")
    name = "城市定向样板 %s" % stamp

    step("0 发布者实名（真实入口，同 chain_platform）")
    status = http("POST", "/api/publisher/identity/status", token=ptok, json_body={})
    if not ((status.get("data") or {}).get("registered")):
        status = http("POST", "/api/publisher/identity", token=ptok, json_body={
            "realName": "测试主理人", "idCard": "110101199001010015", "consent": True, "source": "topic_publish"})
    show("publisher/identity", json.dumps(status, ensure_ascii=False)[:160])

    step("1a 节点玩法模板：到站打卡（POST /api/template/publish，validationMethod=0）")
    tpl = http("POST", "/api/template/publish", token=ptok, json_body={
        "title": "rig 到站打卡 %s" % stamp, "validationMethod": 0, "requestId": "rig-tpl-%s" % stamp})
    show("template/publish", json.dumps(tpl, ensure_ascii=False)[:200])
    tpl_id = tpl.get("data") if isinstance(tpl.get("data"), int) else (tpl.get("data") or {}).get("id")
    if not tpl_id:
        die("模板未建出", tpl)
    review_pending(atok, tpl_id, "%TEMPLATE%")
    readback("template", "SELECT id,member_id,status,draft_status,publish_status FROM cms_member_template WHERE id=%s", (tpl_id,))
    # classic text question (vm=1, the screen that loads the UG-05 hint ladder) with the ladder at the config root
    qa_title = "人民广场的地铁站共有几条线路换乘？"
    qa_cfg = {"schemaVersion": 1,
              "hintFreeUsesPerRun": 1,
              "hintLadder": [{"level": 1, "type": "TEXT", "payload": "看站厅里的线路图"},
                             {"level": 2, "type": "LETTER", "cost": {"points": 30}},
                             {"level": 3, "type": "ANSWER", "cost": {"points": 30}},
                             {"level": 4, "type": "SKIP"}]}
    qa = http("POST", "/api/template/publish", token=ptok, json_body={
        "title": "rig 站台问答 %s" % stamp, "validationMethod": 1, "questionName": qa_title,
        "questionAnswer": "3", "advancedConfigJson": json.dumps(qa_cfg, ensure_ascii=False),
        "requestId": "rig-tpl-qa-%s" % stamp})
    show("template/publish (QA+hintLadder)", json.dumps(qa, ensure_ascii=False)[:240])
    qa_id = qa.get("data") if isinstance(qa.get("data"), int) else (qa.get("data") or {}).get("id")
    if not qa_id:
        die("问答模板未建出", qa)
    review_pending(atok, qa_id, "%TEMPLATE%")
    readback("qa template", "SELECT id,status,publish_status FROM cms_member_template WHERE id=%s", (qa_id,))

    step("1-2 建期：自营城市定向（productType=1），两站有序，零价")
    stations = [("人民广场站", 121.4737, 31.2304), ("南京东路站", 121.4840, 31.2380)]
    # one chapter, two ordered stations: the arrival note (§27.3) renders after station 1 inside the same chapter
    chapters = [{
        "name": "第1章 人民广场到南京东路", "description": "两站有序，先到人民广场",
        "schemaVersion": 1, "required": 1, "recruitEnabled": 0,
        "blocks": [{"type": "text", "content": "从人民广场出发，先到第一站。"}, {"type": "divider"},
                   {"type": "text", "content": "沿着人流走，留意站名。"}, {"type": "node", "nodeIndex": 0},
                   {"type": "text", "content": "再往东走，去南京东路。"}, {"type": "node", "nodeIndex": 1}],
        "nodes": [{"name": n, "address": "上海市黄浦区%s" % n, "longitude": "%.6f" % lng, "clientNodeKey": "st%d" % (k + 1),
                   "latitude": "%.6f" % lat, "nodeTime": 20, "sortID": k + 1,
                   "templateId": tpl_id if k == 0 else qa_id} for k, (n, lng, lat) in enumerate(stations)],
    }]
    if os.environ.get("CHAPTERS") == "2":
        # f-play: 两章各一站(回看与选章要多章)
        nodes = chapters[0]["nodes"]
        chapters = [dict(chapters[0], name="第1章 人民广场", description="先到人民广场",
                         blocks=[{"type": "text", "content": "从人民广场出发，先到第一站。"}, {"type": "divider"},
                                 {"type": "text", "content": "沿着人流走，留意站名。"}, {"type": "node", "nodeIndex": 0},
                                 {"type": "text", "content": "鸽子落回广场，第一章到这里。"}],
                         nodes=[nodes[0]]),
                    dict(chapters[0], name="第2章 南京东路", description="再往东走",
                         blocks=[{"type": "text", "content": "霓虹一盏盏亮起来。"}, {"type": "node", "nodeIndex": 0},
                                 {"type": "text", "content": "答对之后，信封里掉出一张旧车票。"}],
                         nodes=[dict(nodes[1], sortID=1)])]
    theme = {"schemaVersion": 1, "currentScene": "s1", "scenes": [{"id": "s1", "name": "第一幕", "nodeArrivals": {
        "client:st1": {"arrivalReading": {"level": 2, "log": "广场的鸽子一齐飞起"}},
        "client:st2": {"arrivalReading": {"level": 1, "log": "霓虹灯一盏盏亮了"}}}}]}
    dto = {"name": name, "subtitle": "rig", "description": "rig 免费城市定向样板",
           "startDate": d(-1), "endDate": d(60), "saleEndAt": d(45), "categoryIds": "1",
           "productType": 1, "publishMode": "normal", "clubId": CLUB_ID,
           "chapters": chapters, "tickets": [], "themeRuntimeConfig": json.dumps(theme, ensure_ascii=False),
           "imgUrl": seed_approved_cover(ex, q, PUBLISHER_MEMBER)}
    r = http("POST", "/api/topic/v2/create", token=ptok, json_body=dto)
    show("POST /api/topic/v2/create", json.dumps(r, ensure_ascii=False)[:240])
    data = r.get("data")
    tid = data if isinstance(data, int) else ((data or {}).get("topicId") or (data or {}).get("id"))
    if not tid:
        die("建期未返回 topicId", r)
    readback("topic", "SELECT id,product_type,lifecycle,status,user_status,self_play,self_play_price "
             "FROM cms_topic WHERE id=%s", (tid,))

    step("3 内容审核（若有待审任务）")
    review_pending(atok, tid, "TOPIC%")
    state = readback("status/user_status/lifecycle", "SELECT status,user_status,lifecycle FROM cms_topic WHERE id=%s", (tid,))
    if not state or state[0][0] != 1 or state[0][1] != 1 or state[0][2] != 0:
        die("期望 status=1,user_status=1,lifecycle=0(免费草稿态在架)，实际 %s" % (state,))

    step("4 SQL 夹具：零价自玩通行证开关（无写口能存 ¥0 自玩）")
    ex("UPDATE cms_topic SET self_play=1, self_play_price=0.00, self_play_quota=NULL WHERE id=%s", (tid,))
    readback("self_play", "SELECT self_play,self_play_price,self_play_quota FROM cms_topic WHERE id=%s", (tid,))

    if os.environ.get("SKIP_BUY"):
        # f-play: 玩家在小程序里自己走「查看详情与报名 → 确认订单 → 支付 → 开始游戏」
        print("\nTOPIC_ID=%s" % tid); return
    step("5-8 玩家：同意 → 参与人 → 报价 → 领取零价通行证")
    utok, _ = app_token(PLAYER_PHONE)
    if not utok:
        die("玩家登录失败")
    show("consents", (http("POST", "/api/compliance/consents", token=utok, json_body={
        "docType": "activity_host_data_sharing", "scene": "activity_signup", "eventType": "AGREE",
        "requestId": "rig-consent-%s" % stamp}).get("msg") or "")[:40])
    addr = http("POST", "/api/user/address/action", token=utok, form={
        "fullName": PLAYER_NAME, "mobilePhone": PLAYER_PHONE, "province": "上海市",
        "detailAddress": "黄浦区人民大道", "requestId": "rig-addr-%s" % stamp})
    show("address", json.dumps(addr, ensure_ascii=False)[:160])
    quote = http("POST", "/api/registration/quote", token=utok, json_body={"ownerType": 1, "ownerId": tid})
    show("quote", json.dumps(quote, ensure_ascii=False)[:240])
    created = http("POST", "/api/registration/create", token=utok, json_body={
        "ownerType": 1, "ownerId": tid, "realName": PLAYER_NAME, "phone": PLAYER_PHONE,
        "requestId": "rig-free-pass-%s" % stamp})
    show("create", json.dumps(created, ensure_ascii=False)[:240])
    reg = readback("pass", "SELECT id,registration_status,payment_status,purchase_kind,expires_at "
                   "FROM cms_registration WHERE owner_type=1 AND owner_id=%s ORDER BY id", (tid,))
    if not reg or reg[-1][1] != 2:
        die("通行证未完成(registration_status!=2)")

    step("9 玩家进入游玩：GET /api/play/nodes")
    nodes = http("GET", "/api/play/nodes?topicId=%s" % tid, token=utok)
    show("play/nodes", json.dumps(nodes, ensure_ascii=False)[:300])
    if nodes.get("code") != 200:
        die("游玩入口仍被拒", nodes)
    print("\nTOPIC_ID=%s" % tid)


if __name__ == "__main__":
    main()
