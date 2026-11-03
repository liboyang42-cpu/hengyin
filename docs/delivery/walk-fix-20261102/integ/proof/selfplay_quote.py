#!/usr/bin/env python3
"""Integration proof (2026-11-03): the self-play quote states what create charges.

Throwaway cloud-walk rig only (127.0.0.1:8099, MySQL cy_e2e_51 on 127.0.0.1:3316). Reuses pay-gate's rig plumbing
(`pay-gate/proof/rig_env.py`: candidate's own chain_lib + the real /api/registration/callback). payment_status is
written only by the real callback.

Steps
  1. Club 7002's 主理人 creates an ORIENTEERING theme through the real endpoints (same body as pay-gate's
     setup_club_theme.py, plus an APPROVED cover asset fixture from the candidate's chain_platform.seed_approved_cover,
     because l3-sell P-17 now requires a cover): self-play pass 29.99, theme ticket 39.99.
  2. Fixture (printed): approve its audit tasks through the real admin review endpoint; then put the theme on sale
     (lifecycle 3, selling_started_at, user_status 1, check_status 1) because the sale review / media recheck are not
     part of this proof.
  3. Player 9009: POST /api/registration/quote ownerType=1 with the theme ticket (what the rig run in pay-gate did),
     then POST /api/registration/create with that quoteSign, then pay through the real callback.
  4. Read back cms_registration and compare: quote payAmount == payable_amount, quote refund fields == the frozen
     refund snapshot (refund_policy_code START_24H_100_50_0, refund_deadline = fulfilment-window start).
Writes proof/selfplay_quote.json."""
import datetime, json, os, sys, time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE.parents[1] / "pay-gate" / "proof"))
from rig_env import http, q, admin_token, app_token, step, show, readback, j, pay, CLUB_ID, OWNER_PHONE  # noqa: E402

P9009 = "13900000009"
START = int(os.environ.get("INTEG_START_OFFSET", "-1"))  # 主题开始相对今天的天数;-1 = 已开始(同 pay-gate),3 = 三天后
BASE = datetime.datetime.now().replace(hour=9, minute=0, second=0, microsecond=0)
d = lambda off: (BASE + datetime.timedelta(days=off)).strftime("%Y-%m-%d %H:%M:%S")
OUT = {}


def ex(sql, args=()):
    import chain_lib
    n = chain_lib.ex(sql, args)
    show("FIXTURE SQL", (sql % tuple(args)) if args else sql)
    return n


def create_theme():
    atok = admin_token()
    otok, _ = app_token(OWNER_PHONE)
    st = http("POST", "/api/publisher/identity/status", token=otok, json_body={})
    if not (st.get("data") or {}).get("registered"):
        http("POST", "/api/publisher/identity", token=otok, json_body={
            "realName": "测试执行主理人", "idCard": "110101199001010031", "consent": True, "source": "topic_publish"})
    tr = http("POST", "/api/template/draft", token=otok, json_body={"title": "INTEG 到点打卡", "validationMethod": 1, "isSync": 0})
    template_id = tr.get("data")
    show("template", j(tr, 200))
    chapters = [{"name": "第%d章 %s" % (i + 1, n), "description": "集成证明用章节 %s" % n, "schemaVersion": 1,
                 "required": 1, "blocks": [{"type": "text", "content": "到点打卡"}, {"type": "node", "nodeIndex": 0}],
                 "nodes": [{"name": "%s点" % n, "address": "上海市黄浦区%s" % n,
                            "longitude": "%.6f" % (121.47 + i * 0.01), "latitude": "%.6f" % (31.23 + i * 0.01),
                            "nodeTime": 30, "templateId": template_id}]} for i, n in enumerate(["外滩", "豫园"])]
    # P-17 封面:与候选自己的 scripts/e2e/chain_platform.seed_approved_cover 同口径落一条 APPROVED 图片资产(夹具)
    import chain_lib
    from chain_platform import seed_approved_cover
    cover = seed_approved_cover(chain_lib.ex, q, 9008)
    show("FIXTURE cover", cover)
    dto = {"name": "INTEG 自玩报价主题 start%+d" % START, "subtitle": "integ", "description": "集成自玩报价证明",
           "startDate": d(START), "endDate": d(30), "saleEndAt": d(29), "categoryIds": "1",
           "imgUrl": cover,
           "productType": 1, "publishMode": "normal", "clubId": CLUB_ID,
           "selfPlay": 1, "selfPlayPrice": "29.99", "selfPlayQuota": 50, "chapters": chapters,
           "tickets": [{"name": "场次票", "price": "39.99", "totalStock": 50, "mode": 1, "refundSupported": True,
                        "startTime": d(START), "endTime": d(30), "saleStartTime": d(-1), "saleEndTime": d(29)}]}
    r = http("POST", "/api/topic/v2/create", token=otok, json_body=dto)
    show("create theme", j(r, 400))
    data = r.get("data")
    tid = data if isinstance(data, int) else ((data or {}).get("topicId") or (data or {}).get("id"))
    if not tid:
        raise SystemExit("theme create failed")
    for task_id, biz in q("SELECT id,biz_type FROM audit_task WHERE biz_id=%s AND status=0 ORDER BY id", (tid,)):
        rr = http("POST", "/business/audit/review", token=atok,
                  json_body={"taskId": task_id, "status": 1, "reasonCode": "E2E", "reasonText": "集成放行"})
        show("review %s %s" % (task_id, biz), (rr.get("msg") or "")[:60])
    pr = http("POST", "/api/topic/pricing/confirm", token=otok, json_body={"topicId": tid, "guidedPrice": 39.99,
              "guidedInventory": 20, "leadCost": 0, "teamSize": 6})
    show("pricing/confirm (真实入口,落费率快照)", j(pr))
    return tid


def main():
    step("1. 建主题(真实入口)")
    tid = create_theme()
    step("2. 夹具:上架开售(打印全部写入)")
    ex("UPDATE cms_topic SET lifecycle=3, selling_started_at=DATE_SUB(NOW(), INTERVAL 1 HOUR), status=1, user_status=1, "
       "check_status=1 WHERE id=%s", (tid,))
    OUT["topic"] = readback("topic", "SELECT id,club_id,product_type,lifecycle,status,user_status,self_play,self_play_price,"
                            "start_date,end_date,sale_end_at FROM cms_topic WHERE id=%s", (tid,))
    ticket = q("SELECT id FROM oms_ticket WHERE owner_type=1 AND owner_id=%s AND del_flag=0 ORDER BY id LIMIT 1", (tid,))[0][0]
    OUT["ticket"] = readback("theme ticket", "SELECT id,owner_type,owner_id,price,start_time,end_time FROM oms_ticket "
                             "WHERE id=%s", (ticket,))
    step("3. 玩家 9009 报价 → 下单 → 付款(真实回调)")
    ptok = app_token(P9009)[0]
    http("POST", "/api/compliance/consents", token=ptok, json_body={
        "docType": "activity_host_data_sharing", "scene": "activity_signup", "eventType": "AGREE",
        "requestId": "integ-consent-%d" % int(time.time())})
    body = {"ownerType": 1, "ownerId": tid, "ticketId": ticket, "realName": "E2E 玩家", "phone": P9009}
    quote = http("POST", "/api/registration/quote", token=ptok, json_body=body)
    show("quote", j(quote, 500))
    body["quoteSign"] = (quote.get("data") or {}).get("quoteSign")
    body["requestId"] = "integ-selfplay-%d" % int(time.time())
    created = http("POST", "/api/registration/create", token=ptok, json_body=body)
    show("create", j(created, 400))
    data = created.get("data") or {}
    reg, reg_no = data.get("registrationId"), data.get("registrationNo")
    cb = pay(reg_no, reg) if reg else {}
    rows = q("SELECT id,payable_amount,wechat_payment_amount,point_used,purchase_kind,refund_policy_code,"
             "refund_deadline,payment_status,registration_status FROM cms_registration WHERE id=%s", (reg,)) if reg else []
    OUT["quote"] = quote.get("data")
    OUT["create"] = {k: created.get(k) for k in ("code", "msg", "data")}
    OUT["callback"] = cb.get("_status")
    OUT["registration"] = readback("cms_registration", "SELECT id,payable_amount,wechat_payment_amount,point_used,"
                                   "purchase_kind,refund_policy_code,refund_deadline,payment_status,registration_status "
                                   "FROM cms_registration WHERE id=%s", (reg,)) if reg else None
    step("4. 对账")
    qd = quote.get("data") or {}
    checks = {}
    if rows:
        r = rows[0]
        checks["quote.payAmount == payable_amount"] = (str(qd.get("payAmount")), str(r[1]),
                                                       abs(float(qd.get("payAmount") or -1) - float(r[1])) < 1e-9)
        checks["quote.pointsUsed == point_used"] = (qd.get("pointsUsed"), r[3], int(qd.get("pointsUsed") or 0) == int(r[3] or 0))
        anchor = r[6]
        if anchor is not None and r[5] == "START_24H_100_50_0":
            want = (anchor - datetime.timedelta(hours=24))
            got = qd.get("refundDeadline")
            checks["quote.refundDeadline == frozen start - 24h"] = (str(got), want.strftime("%Y-%m-%d %H:%M:%S"),
                                                                    bool(got) and str(got).replace("T", " ").startswith(want.strftime("%Y-%m-%d %H:%M")))
            # 与 RefundPolicy 通行证三档同一口钟:开始前 24h 以上 100,24h 内 50,开始后 0(按回读的冻结锚点在本机算)
            now = datetime.datetime.now()
            expect = 100 if now < anchor - datetime.timedelta(hours=24) else (50 if now < anchor else 0)
            checks["quote.refundPercentNow == tier at frozen anchor"] = (qd.get("refundPercentNow"), expect,
                                                                         qd.get("refundPercentNow") == expect)
            checks["quote.refundableNow == (tier > 0)"] = (qd.get("refundableNow"), expect > 0,
                                                           qd.get("refundableNow") == (expect > 0))
    for k, v in checks.items():
        show(k, v)
    OUT["checks"] = {k: list(v) for k, v in checks.items()}
    OUT["pass"] = all(v[2] is not False for v in checks.values()) and bool(rows)
    (HERE / ("selfplay_quote_start%+d.json" % START)).write_text(json.dumps(OUT, ensure_ascii=False, indent=1, default=str), encoding="utf-8")
    print("\nRESULT", "PASS" if OUT["pass"] else "FAIL")
    return 0 if OUT["pass"] else 1


if __name__ == "__main__":
    sys.exit(main())
