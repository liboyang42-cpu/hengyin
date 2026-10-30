#!/usr/bin/env python3
"""5-1 玩家段 + 商家核销段:开今天这一场 → 买票 → 支付 → 四店打卡+核销 → 通关图鉴。

用法: python3 scripts/e2e/chain_player.py <topicId>
前置: chain_sale_close.py 已把主题带到 SELLING。

★ 支付这一层需要一个【桩】,见文件末尾说明。桩只存在于本地 e2e 运行时,仓库产品码一行都没有。
"""
import sys, pathlib, json, time, hashlib, datetime
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from chain_lib import http, q, ex, admin_token, app_token, step, show, readback

EXEC_CLUB_ID, EXEC_CLUB_PHONE = 7002, "13900000008"
PLAYER_PHONE, PLAYER_MEMBER = "13900000009", 9009
MERCHANT_PHONE_BY_MEMBER = {9002: "13900000002", 9005: "13900000005",
                            9006: "13900000006", 9007: "13900000007"}


FIXTURE_MCHID, FIXTURE_APPID = "E2EMCH0001", "wxe2e00000000001"


def fact_hash(values):
    """与 WechatVerifiedPaymentFact.canonicalFacts()/factHash() 逐字节同口径:每项「UTF-8 字节长:值」,null 记 -1:。"""
    out = []
    for value in values:
        out.append("-1:" if value is None else "%d:%s" % (len(value.encode("utf-8")), value))
    return hashlib.sha256("".join(out).encode("utf-8")).hexdigest()


def store_trusted_payment_fact(reg_no, transaction_id, total_fen):
    """★夹具扮演「已验签的支付渠道」:把一条 VERIFIED 原始支付事实写进测试库。

    桩通道自己从不造事实(E2eWechatPayStub 只消费已存在的原始事实);生产里这一行由 SDK 查单验签后写入。
    有冻结的支付尝试(wx_pay_order_intent)就逐项照抄它,没有就按「业务单号即渠道单号、无意图哈希」的历史口径。
    """
    intent = q("SELECT out_trade_no,mchid,appid,trade_type,currency,total_fen,request_hash FROM wx_pay_order_intent "
               "WHERE business_out_trade_no=%s AND closed_confirmed_at IS NULL ORDER BY attempt_no DESC LIMIT 1", (reg_no,))
    if intent:
        wx_no, mchid, appid, trade_type, currency, total, request_hash = intent[0]
        total = int(total)
    else:
        wx_no, mchid, appid, trade_type, currency, total, request_hash = (
            reg_no, FIXTURE_MCHID, FIXTURE_APPID, "JSAPI", "CNY", total_fen, None)
    success_at = datetime.datetime.now(datetime.timezone.utc).replace(microsecond=0).strftime("%Y-%m-%dT%H:%M:%SZ")
    digest = fact_hash(["wx-success-fact-v1", "PAYMENT", mchid, appid, reg_no, wx_no, wx_no, transaction_id,
                        transaction_id, trade_type, currency, str(total), str(total), success_at, request_hash])
    ex("INSERT INTO wx_verified_payment_fact (fact_kind,mchid,appid,business_out_trade_no,wx_out_trade_no,"
       "merchant_event_no,provider_id,transaction_id,trade_type,currency,total_fen,amount_fen,success_at,"
       "intent_request_hash,fact_hash,verification_version,verification_outcome,verification_method,original_payment_state) "
       "VALUES ('PAYMENT',%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,1,'VERIFIED','SDK_QUERY_ORIGINAL_ORDER','SUCCESS')",
       (mchid, appid, reg_no, wx_no, wx_no, transaction_id, transaction_id, trade_type, currency,
        total, total, success_at, request_hash, digest))
    fact_id = q("SELECT id FROM wx_verified_payment_fact WHERE fact_hash=%s", (digest,))[0][0]
    return {"verified_fact_id": str(fact_id), "verified_fact_hash": digest, "wx_out_trade_no": wx_no,
            "mchid": mchid, "appid": appid, "trade_type": trade_type, "currency": currency}


def assert_paid_callback(callback, paid_rows, expected_transaction_id):
    if callback.get("_status") != 200 or callback.get("code") != "SUCCESS":
        raise RuntimeError("支付回调协议未成功:%s" % callback)
    if (len(paid_rows) != 1 or paid_rows[0][2] != 2 or paid_rows[0][3] is None
            or paid_rows[0][4] != expected_transaction_id):
        raise RuntimeError("报名单已支付事实未闭环:%s" % (paid_rows,))


def main(tid):
    atok = admin_token()
    etok, _ = app_token(EXEC_CLUB_PHONE)
    ptok, _ = app_token(PLAYER_PHONE)

    step("平台:内容槽改判为 PLATFORM(③-自营)—— 二清闸只放行自营局收钱")
    # ⚠️ 这【不是】绕闸:assertCollectible(CmsRegistrationServiceImpl:1334-1343)是 2026-07-16 拍板的
    #   二清闸,过渡期内只有 CONTENT 槽 = PLATFORM 的③-自营局可以真实收款。非自营局在今天
    #   本来就不该能在线收钱,所以要跑收费票链路,主题就必须是自营局。真实入口在后台。
    show("content-slot", (http("PUT", "/business/topic/%d/content-slot" % tid, token=atok,
                               json_body={"entityType": "PLATFORM"}).get("msg") or "")[:30])
    readback("product_role", "SELECT slot,entity_type,entity_id,status FROM product_role "
             "WHERE topic_id=%s ORDER BY id", (tid,))

    step("俱乐部:开【应用眼里的今天】这一场 —— 活动时间闸按 cms_activity 起止判")
    # ⚠️ 时区:MySQL 会话是 UTC、应用 JVM 是 GMT+8,ClubSession.dayBoundary 按 JVM 算。
    #   直接拿 MySQL 的 NOW() 日期去开团会开成昨天,当场「活动已结束」。这里取 JVM 那侧的今天。
    #   开团要求集合时间晚于当前(ClubSessionServiceImpl 5.9 拍板)，场次 start_date=集合时刻，
    #   开始后不再收报名、开始前不能游玩 ⇒ 取 JVM 侧「现在 + 3 分钟」，集合前报名支付，到点后再到店。
    today, session_time = q("SELECT DATE_FORMAT(DATE_ADD(NOW(), INTERVAL 8 HOUR) + INTERVAL 3 MINUTE, '%%Y-%%m-%%d'), "
                            "DATE_FORMAT(DATE_ADD(NOW(), INTERVAL 8 HOUR) + INTERVAL 3 MINUTE, '%%H:%%i')")[0]
    r = http("POST", "/api/club/open-session", token=etok,
             json_body={"clubId": EXEC_CLUB_ID, "topicId": tid,
                        "sessionDate": today, "sessionTime": session_time})
    show("open-session %s" % today, json.dumps(r, ensure_ascii=False)[:140])
    act = (r.get("data") or {}).get("activityId")
    if not act:
        print("   ✗ 开团失败,链路在此中断"); return 1
    readback("今天这场", "SELECT id,start_date,end_date FROM cms_activity WHERE id=%s", (act,))

    step("玩家:报名信息单独同意 → 报价 → 建单")
    show("consents", (http("POST", "/api/compliance/consents", token=ptok, json_body={
        "docType": "activity_host_data_sharing", "scene": "activity_signup",
        "eventType": "AGREE", "requestId": "e2e-consent-%d" % tid}).get("msg") or "")[:20])
    ticket = q("SELECT id FROM oms_ticket WHERE owner_type=2 AND owner_id=%s ORDER BY id LIMIT 1", (act,))[0][0]
    body = {"ownerType": 2, "ownerId": act, "ticketId": ticket,
            "tickets": [{"ticketId": ticket, "count": 1}],
            "realName": "E2E 玩家二号", "phone": PLAYER_PHONE}
    quote = http("POST", "/api/registration/quote", token=ptok, json_body=body)
    show("quote", json.dumps(quote.get("data"), ensure_ascii=False)[:140])
    if not quote.get("data"):
        show("quote 原始响应", json.dumps(quote, ensure_ascii=False)[:300])
        print("   ✗ 报价失败,链路在此中断"); return 1
    body["quoteSign"] = quote["data"]["quoteSign"]
    body["requestId"] = "e2e-reg-%d-1" % tid
    created = http("POST", "/api/registration/create", token=ptok, json_body=body)
    show("create", json.dumps(created, ensure_ascii=False)[:180])
    if not created.get("data"):
        print("   ✗ 建单失败,链路在此中断"); return 1
    reg, reg_no = created["data"]["registrationId"], created["data"]["registrationNo"]
    entitlements = readback("★registration_entitlement 应四条(每必选章一条,status=0)",
                            "SELECT id,chapter_id,status FROM registration_entitlement WHERE registration_id=%s "
                            "ORDER BY chapter_id", (reg,))
    if len(entitlements) != 4 or any(row[2] != 0 for row in entitlements):
        print("   ✗ 建单后权益数量或初始状态不符合四章合同"); return 1

    step("支付成功:走【真实回调入口】POST /api/registration/callback,受控参数明文喂进去")
    # ★桩:支付可用性(环境依赖)。桩掉的只有外部支付通道(商户凭据/SDK/验签/预下单);
    #   金额冻结校验、订单状态机、entitlement 等业务侧一条都没绕过,更没有直接改 payment_status。
    fact = store_trusted_payment_fact(reg_no, "E2E-TXN-%d" % reg, 6900)
    show("trusted fact", "id=%s" % fact["verified_fact_id"])
    callback = http("POST", "/api/registration/callback", json_body=dict(fact, **{
        "out_trade_no": reg_no, "transaction_id": "E2E-TXN-%d" % reg,
        "trade_state": "SUCCESS", "total": 6900}))
    show("callback", json.dumps(callback, ensure_ascii=False)[:120])
    paid_rows = readback("报名单", "SELECT id,registration_no,payment_status,payment_time,transaction_id "
                         "FROM cms_registration WHERE id=%s", (reg,))
    try:
        assert_paid_callback(callback, paid_rows, "E2E-TXN-%d" % reg)
    except RuntimeError as exc:
        print("   ✗ %s" % exc)
        return 1

    step("等到集合时间:场次开始后才可到店游玩(报名已在集合前完成)")
    for _ in range(60):
        if q("SELECT DATE_ADD(NOW(), INTERVAL 8 HOUR) >= start_date FROM cms_activity WHERE id=%s", (act,))[0][0]:
            break
        time.sleep(5)
    else:
        print("   ✗ 场次 300 秒内未到集合时间,链路在此中断"); return 1
    show("场次已开始", "JVM 时钟已过集合时间")

    step("四店:玩家到店扫码打卡 → 商家扫玩家动态码核销")
    nodes = q("SELECT id,chapter_id,checkin_code,merchant_member_id FROM cms_topic_node "
              "WHERE topic_id=%s AND merchant_member_id IS NOT NULL ORDER BY chapter_id", (tid,))
    if len(nodes) != 4:
        print("   ✗ 到店节点不是四章"); return 1
    for _nid, chapter_id, code, merchant in nodes:
        mtok, _ = app_token(MERCHANT_PHONE_BY_MEMBER[merchant])
        show("玩家打卡 ch%s" % chapter_id, (http("POST", "/api/play/checkin", token=ptok,
             form={"activityId": act, "code": code, "topicId": tid}).get("msg") or "")[:24])
        dyn = http("POST", "/api/verify/dyncode/issue", token=ptok,
                   form={"registrationId": reg, "type": "activity"})
        show("商家%s 核销 ch%s" % (merchant, chapter_id), json.dumps(http(
            "POST", "/api/registration/scan_qr_code", token=mtok,
            form={"type": "activity", "code": dyn["data"]["code"]}), ensure_ascii=False)[:140])
    entitlements = readback("★entitlement 应全部 0→1", "SELECT chapter_id,status,redeemed_at FROM registration_entitlement "
                            "WHERE registration_id=%s ORDER BY chapter_id", (reg,))
    redemptions_rows = readback("★redemption 应四行 ACTIVE", "SELECT chapter_id,merchant_id,status,create_time FROM redemption "
                                 "WHERE registration_id=%s ORDER BY chapter_id", (reg,))
    if len(entitlements) != 4 or any(row[1] != 1 or row[2] is None for row in entitlements):
        print("   ✗ 四章权益未全部核销"); return 1
    if len(redemptions_rows) != 4 or any(row[2] != "ACTIVE" or row[3] is None for row in redemptions_rows):
        print("   ✗ redemption 四章事实未闭环"); return 1

    step("★等待事实:queue_joined_at 非空 且 service_started_at == 该章 redemption.create_time")
    # 判据来自 TopicOnsiteMetricsMapper.selectWaitFacts 本身:
    # queue_joined_at = min(player_node_progress.checkin_time),service_started_at = min(redemption.create_time)
    waits = q("""select e.chapter_id, min(p.checkin_time), min(rd.create_time)
      from registration_entitlement e
      join cms_registration r on r.id = e.registration_id
      join cms_activity a on a.id = r.owner_id and r.owner_type = 2 and a.topic_id = %s
      left join cms_topic_node n on n.chapter_id = e.chapter_id and n.topic_id = %s
      left join player_node_progress p on p.member_id = r.member_id and p.activity_id = r.owner_id
           and p.topic_id = %s and p.node_id = n.id and p.del_flag = 0
      left join redemption rd on rd.registration_id = r.id and rd.chapter_id = e.chapter_id
           and rd.topic_id = %s and rd.status = 'ACTIVE'
     where e.topic_id = %s and e.registration_id = %s
       and (p.checkin_time is not null or rd.id is not null)
     group by e.chapter_id order by e.chapter_id""", (tid, tid, tid, tid, tid, reg))
    redemptions = dict((c, t) for c, t in q("SELECT chapter_id,create_time FROM redemption "
                                            "WHERE registration_id=%s", (reg,)))
    for chapter_id, queue_joined_at, service_started_at in waits:
        print("   ch%-4s queue_joined_at=%s  service_started_at=%s  == redemption.create_time: %s"
              % (chapter_id, queue_joined_at, service_started_at,
                 service_started_at == redemptions.get(chapter_id)))
    waits_ok = len(waits) == 4 and all(w[1] is not None for w in waits)
    service_ok = len(waits) == 4 and all(w[2] == redemptions.get(w[0]) for w in waits)
    print("   四章 queue_joined_at 全非空: %s | service_started_at 全等于 redemption.create_time: %s"
          % (waits_ok, service_ok))
    if not waits_ok or not service_ok:
        print("   ✗ 等待时长事实未闭环"); return 1

    step("★通关图鉴落行")
    completion = http("POST", "/api/registration/explore-completion",
                      token=ptok, form={"id": reg})
    show("explore-completion", json.dumps(completion, ensure_ascii=False)[:300])
    collectibles = readback("member_collectible(一章一枚)", "SELECT id,coll_type,topic_id,ref_id,LEFT(content,40) "
                            "FROM member_collectible WHERE member_id=%s AND topic_id=%s ORDER BY ref_id",
                            (PLAYER_MEMBER, tid))
    badges = readback("player_badge(通关勋章)", "SELECT id,badge_code,badge_name,source_id FROM player_badge "
                      "WHERE member_id=%s AND source_id=%s", (PLAYER_MEMBER, tid))
    if completion.get("code") != 200 or len(collectibles) != 4 or len(badges) != 1:
        print("   ✗ 四章图鉴或通关勋章未闭环"); return 1
    print("\nACTIVITY_ID=%s REGISTRATION_ID=%s" % (act, reg))
    return 0


if __name__ == "__main__":
    sys.exit(main(int(sys.argv[1])))
