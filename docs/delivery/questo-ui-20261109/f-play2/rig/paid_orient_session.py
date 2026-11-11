#!/usr/bin/env python3
"""Rig-only fixture (f-play2, from qx-c5 activity_orient_theme_c5.py): a PAID 城市定向 session ticket.

Same real endpoints as qx-c5 (template publish -> v2/create -> audit -> /business/ticket -> /api/club/open-session
by the content-owner club 7001), with these f-play2 differences:
  * two chapters, one station each (ch1 到站打卡, ch2 问答 answer "3") so post-finish review and the chapter
    picker have more than one chapter (same shape as f-play CHAPTERS=2);
  * the guided ticket costs 69.00 (paid), and the content slot is set to PLATFORM through the real admin entry
    PUT /business/topic/{id}/content-slot, exactly as scripts/e2e/chain_player.py does (the 二清闸 only lets
    PLATFORM-slot sessions collect money today; this is the current on-sale shape, not a bypass);
  * SKIP_BUY=1 stops after the player's consent / participant / club-join prep so the player buys the ticket in
    the browser (topic detail -> 确认订单 -> wx.requestPayment -> rig pay seam -> real /api/registration/callback).
No SQL writes except the approved cover media asset (same helper as chain_platform).
Usage: CAND=... RIG_SESSION_LEAD_MIN=6 SKIP_BUY=1 $RIG_HOME/venv/bin/python paid_orient_session.py
Prints TOPIC_ID / ACTIVITY_ID.
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
from seed_fixtures import RECRUIT_JOB_TARGET  # noqa: E402

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
    name = "城市定向场次票 %s" % stamp

    reuse = os.environ.get("REUSE_TOPIC")
    if reuse:
        # 主题上架数有上限（2）：复用本脚本上一轮已建好的同一份主题，只重新开一场。
        tid = int(reuse)
        readback("reused topic", "SELECT id,product_type,lifecycle,status,user_status FROM cms_topic WHERE id=%s", (tid,))
    else:
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

        step("1-2 建期：自营城市定向（productType=1），两章各一站")
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
        dto = {"name": name, "subtitle": "rig", "description": "rig 收费场次票城市定向",
               "startDate": d(-1), "endDate": d(60), "saleEndAt": d(45), "categoryIds": "1",
               "productType": 1, "publishMode": "normal", "clubId": CLUB_ID,
               "recruitDeadline": d(1), "chapters": chapters, "tickets": [{
                   "name": "城市定向场次票", "price": 69, "mode": 2, "totalStock": 20,
                   "startTime": d(-1)[:10] + " 10:00:00", "endTime": d(3),
                   "signupDeadline": d(2),
                   "meetingPoint": "人民广场1号口", "gatherLng": 121.473701, "gatherLat": 31.230416,
                   "refundRule": "开场前24小时可退"}], "themeRuntimeConfig": json.dumps(theme, ensure_ascii=False),
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
        if not state or state[0][0] != 1 or state[0][1] != 1 or state[0][2] != 1:
            die("期望 status=1,user_status=1,lifecycle=1(收费票 ⇒ 商业主题，立项审后进 RECRUITING)，实际 %s" % (state,))

        step("3b0 发布者 → 执行俱乐部 7002 发 type1 合作邀约，俱乐部接受 ⇒ ORGANIZER coop_order（同 chain_merchant_club）")
        # 招募到期任务：0 接受 → 回草稿；≥1 接受 → PRICING（TopicRecruitDeadlineTask）。必须在招募期内、锁价前做。
        r = http("POST", "/api/coop/invite", token=ptok, json_body={
            "topicId": tid, "inviteType": 1, "toType": "club", "toId": 7002,
            "shareMode": 0, "message": "rig 请俱乐部承接执行"})
        show("coop/invite", json.dumps(r, ensure_ascii=False)[:180])
        etok, _ = app_token("13900000008")
        for (iid,) in q("SELECT id FROM coop_invite WHERE topic_id=%s AND status=0 ORDER BY id", (tid,)):
            show("coop/handle accept %s" % iid, json.dumps(http("POST", "/api/coop/handle", token=etok,
                 json_body={"id": iid, "status": 1}), ensure_ascii=False)[:180])
        readback("coop_order", "SELECT id,role_snapshot,status FROM coop_order WHERE topic_id=%s", (tid,))

        step("3c 招募到期任务：RECRUITING → PRICING（topicRecruitDeadlineTask，同 chain_merchant_club）")
        # 只把等待压缩为立即执行（同 chain_merchant_club 的唯一 SQL）；任务本身走后台真实入口。
        ex("UPDATE cms_topic SET recruit_deadline = DATE_SUB(NOW(), INTERVAL 1 MINUTE) WHERE id=%s", (tid,))
        job = q("SELECT job_id,job_group FROM sys_job WHERE invoke_target=%s", (RECRUIT_JOB_TARGET,))
        show("run job", json.dumps(http("PUT", "/monitor/job/run", token=atok,
             json_body={"jobId": job[0][0], "jobGroup": job[0][1]}), ensure_ascii=False)[:120])
        time.sleep(5)
        if readback("lifecycle(应为 2=PRICING)", "SELECT lifecycle FROM cms_topic WHERE id=%s", (tid,))[0][0] != 2:
            die("招募到期未进入 PRICING")

        step("3d 俱乐部主理人锁价（POST /api/topic/pricing/confirm）")
        r = http("POST", "/api/topic/pricing/confirm", token=ptok, json_body={
            "topicId": tid, "guidedPrice": 69, "guidedInventory": 20, "leadCost": 0, "teamSize": 6})
        show("pricing/confirm", json.dumps(r, ensure_ascii=False)[:220])
        if r.get("code") != 200:
            die("锁价失败", r)

    step("3a 平台：内容槽改判为 PLATFORM（同 chain_player，真实后台入口；二清闸只放行自营局收钱）")
    show("content-slot", (http("PUT", "/business/topic/%d/content-slot" % tid, token=atok,
                               json_body={"entityType": "PLATFORM"}).get("msg") or "")[:30])
    readback("product_role", "SELECT slot,entity_type,status FROM product_role WHERE topic_id=%s ORDER BY id", (tid,))

    if not reuse:
        step("3e 开售提审 → 审核通过 → SELLING（POST /business/audit/topic/{id}/sale-submit）")
        show("sale-submit", json.dumps(http("POST", "/business/audit/topic/%d/sale-submit" % tid, token=atok),
                                       ensure_ascii=False)[:240])
        for (task_id,) in q("SELECT id FROM audit_task WHERE biz_id=%s AND biz_type='TOPIC_SALE_REVIEW' AND status=0", (tid,)):
            show("review %s" % task_id, json.dumps(http("POST", "/business/audit/review", token=atok, json_body={
                "taskId": task_id, "status": 1, "reasonCode": "RIG", "reasonText": "rig 放行"}), ensure_ascii=False)[:200])
        st = readback("lifecycle/selling_started_at", "SELECT lifecycle,selling_started_at FROM cms_topic WHERE id=%s", (tid,))
        if not st or st[0][0] != 3:
            die("未进入 SELLING(3)")

    step("3b 主题层 69.00 guided 票种模板（后台 POST /business/ticket，同 chain_merchant_club；open-session 复制到场次）")
    if not q("SELECT id FROM oms_ticket WHERE owner_type=1 AND owner_id=%s AND del_flag=0", (tid,)):  # f-play2: 建期已带收费票(sub_type 为空),有就不再建
        base = q("SELECT start_date FROM cms_topic WHERE id=%s", (tid,))[0][0].replace(hour=10, minute=0, second=0, microsecond=0)
        tr = http("POST", "/business/ticket", token=atok, json_body={
            "ownerType": 1, "ownerId": tid, "name": "城市定向场次票", "subType": "guided", "mode": 2,
            "price": 69.00, "totalInventory": 20, "delFlag": 0,
            "startTime": base.strftime("%Y-%m-%d %H:%M:%S"),
            "endTime": (base + datetime.timedelta(hours=4)).strftime("%Y-%m-%d %H:%M:%S"),
            "signupDeadline": (base - datetime.timedelta(hours=2)).strftime("%Y-%m-%d %H:%M:%S"),
            "meetingPoint": "人民广场1号口", "gatherLng": "121.473701", "gatherLat": "31.230416",
            "refundRule": "开场前24小时可退", "memberId": PUBLISHER_MEMBER})
        show("business/ticket", json.dumps(tr, ensure_ascii=False)[:200])
    # open-session 把模板的 signupDeadline 原样复制到场次票（OmsTicketServiceImpl.newActivityTicket），
    # 所以模板截止时间须晚于本场集合前的报名时刻；改用后台同一入口 PUT。
    for (ticket_id,) in ([] if reuse or True else []):  # f-play2: 票种在建期已带未来截止,SELLING 后目录冻结,不再 PUT
        tu = http("PUT", "/business/ticket", token=atok, json_body={
            "id": ticket_id, "ownerType": 1, "ownerId": tid, "signupDeadline": d(2), "endTime": d(3)})
        show("business/ticket PUT signupDeadline", json.dumps(tu, ensure_ascii=False)[:200])
    readback("主题票", "SELECT id,name,sub_type,price,start_time,end_time,signup_deadline FROM oms_ticket WHERE owner_type=1 AND owner_id=%s AND del_flag=0", (tid,))

    step("4 俱乐部开一场（POST /api/club/open-session，club 7001 是主题所属俱乐部 ⇒ clubOwned，不需要合作单）")
    # 时区：应用 JVM 是 GMT+8、MySQL 会话是 UTC，同 chain_player 取 JVM 侧今天；集合时间须晚于当前。
    lead = int(os.environ.get("RIG_SESSION_LEAD_MIN", "2"))
    today, session_time = q("SELECT DATE_FORMAT(DATE_ADD(NOW(), INTERVAL 8 HOUR) + INTERVAL %s MINUTE, '%%Y-%%m-%%d'), "
                            "DATE_FORMAT(DATE_ADD(NOW(), INTERVAL 8 HOUR) + INTERVAL %s MINUTE, '%%H:%%i')", (lead, lead))[0]
    opened = http("POST", "/api/club/open-session", token=ptok, json_body={
        "clubId": CLUB_ID, "topicId": tid, "sessionDate": today, "sessionTime": session_time})
    show("open-session %s %s" % (today, session_time), json.dumps(opened, ensure_ascii=False)[:240])
    act = (opened.get("data") or {}).get("activityId") if isinstance(opened.get("data"), dict) else opened.get("data")
    if not act:
        die("开团失败", opened)
    readback("activity", "SELECT id,topic_id,club_id,status,start_date,end_date FROM cms_activity WHERE id=%s", (act,))
    for (task_id, biz_type, version) in q("SELECT id,biz_type,version FROM audit_task WHERE biz_id=%s AND status=0 "
                                          "AND biz_type LIKE %s ORDER BY id", (act, "%ACTIVITY%")):
        rr = http("POST", "/business/audit/review", token=atok, json_body={
            "taskId": task_id, "status": 1, "expectedVersion": version, "reasonCode": "RIG", "reasonText": "rig 放行"})
        show("review %s(%s) v%s" % (task_id, biz_type, version), (rr.get("msg") or "")[:60])
    tickets = readback("场次票", "SELECT id,price FROM oms_ticket WHERE owner_type=2 AND owner_id=%s ORDER BY id", (act,))

    step("5-8 玩家：同意 → 参与人 → 报价 → 报名本场（ownerType=2）")
    utok, _ = app_token(PLAYER_PHONE)
    if not utok:
        die("玩家登录失败")
    show("consents", (http("POST", "/api/compliance/consents", token=utok, json_body={
        "docType": "activity_host_data_sharing", "scene": "activity_signup", "eventType": "AGREE",
        "requestId": "rig-consent-%s" % stamp}).get("msg") or "")[:40])
    addr = http("POST", "/api/user/address/action", token=utok, form={
        "fullName": PLAYER_NAME, "mobilePhone": PLAYER_PHONE, "province": "上海市",
        "detailAddress": "黄浦区人民大道", "requestId": "rig-addr-%s" % stamp})
    show("address", (addr.get("msg") or "")[:40])
    joined = http("POST", "/api/club/join", token=utok, json_body={"id": CLUB_ID, "joinMessage": "rig 想参加本场"})
    show("club/join", json.dumps(joined, ensure_ascii=False)[:160])
    if ((joined.get("data") or {}).get("state") or "").upper().startswith("PEND"):
        player_member = q("SELECT member_id FROM ums_member_openid WHERE openid=%s", ("e2e-openid-" + PLAYER_PHONE[-4:],))
        player_id = player_member[0][0] if player_member else 9004
        ok = http("POST", "/api/club/join-request/approve", token=ptok, json_body={"clubId": CLUB_ID, "memberId": player_id})
        show("club/join-request/approve", json.dumps(ok, ensure_ascii=False)[:160])
    readback("club_member", "SELECT club_id,member_id,status FROM club_member WHERE club_id=%s AND member_id=9004", (CLUB_ID,))
    if os.environ.get("SKIP_BUY"):
        # f-play2: 玩家在小程序里自己买票（详情 → 确认订单 → 支付 → 票夹）
        print("\nTOPIC_ID=%s\nACTIVITY_ID=%s" % (tid, act)); return
    body = {"ownerType": 2, "ownerId": act, "realName": PLAYER_NAME, "phone": PLAYER_PHONE}
    if tickets:
        body.update({"ticketId": tickets[0][0], "tickets": [{"ticketId": tickets[0][0], "count": 1}]})
    quote = http("POST", "/api/registration/quote", token=utok, json_body=body)
    show("quote", json.dumps(quote, ensure_ascii=False)[:240])
    if (quote.get("data") or {}).get("quoteSign"):
        body["quoteSign"] = quote["data"]["quoteSign"]
    body["requestId"] = "rig-act-reg-%s" % stamp
    created = http("POST", "/api/registration/create", token=utok, json_body=body)
    show("create", json.dumps(created, ensure_ascii=False)[:240])
    reg = readback("registration", "SELECT id,registration_status,payment_status,owner_type,owner_id "
                   "FROM cms_registration WHERE owner_type=2 AND owner_id=%s ORDER BY id", (act,))
    if not reg:
        die("报名未落库")

    step("9 等到集合时间后玩家进入游玩：GET /api/play/nodes?activityId=")
    deadline = time.time() + 60 * (lead + 2)
    while True:
        nodes = http("GET", "/api/play/nodes?topicId=%s&activityId=%s" % (tid, act), token=utok)
        if nodes.get("code") == 200 or time.time() > deadline:
            break
        time.sleep(15)
    show("play/nodes", json.dumps(nodes, ensure_ascii=False)[:300])
    if nodes.get("code") != 200:
        die("游玩入口仍被拒", nodes)
    print("\nTOPIC_ID=%s\nACTIVITY_ID=%s" % (tid, act))


if __name__ == "__main__":
    main()
