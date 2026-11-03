#!/usr/bin/env python3
"""pay-gate Round 3D money proof over real HTTP with database read-back (throwaway cloud-walk rig only).

Theme 990016 「R3D 俱乐部自玩主题」 is club 7002's own classic theme, built by setup_club_theme.py through the real
template/create/audit/pricing endpoints. payment_status is written only by the real /api/registration/callback after
chain_player.store_trusted_payment_fact (rig_env.pay). Fixture writes, all on the test DB and all printed below (plus, before the settlement phases, the existing unchanged
migration_20260815_tandianri_h8_publisher_points_funds.sql applied to the rig DB with ci/run_migration.py: the rig's
schema builder had skipped it, so the post-cash points award failed with "Unknown column rule_version"):
  * cms_topic 990016 lifecycle 2 -> 3 (the sale review is blocked by SALE_ORGANIZER_ONLY_FREE_EXPLORE, lane l3-sell);
  * moving a session's / the theme's dates into the past to reach "after start" and "settlement due" without waiting.
Usage: python proof_round3d.py <phase>   phases: tiers | transfer | settle_activity | selfplay | settle_topic | payout
Each phase appends its result JSON to proof/round3d-<phase>.json."""
import datetime, json, sys, time
from rig_env import http, q, admin_token, app_token, step, show, readback, j, pay, CLUB_ID, OWNER_PHONE, OWNER_ID

TOPIC = 990016
P9009, P9004 = "13900000009", "13900000004"
OUT = {}


def ex(sql, args=()):
    import chain_lib
    n = chain_lib.ex(sql, args)
    show("FIXTURE SQL", (sql % tuple(args)) if args else sql)
    return n


def cst_now():
    return datetime.datetime.utcnow() + datetime.timedelta(hours=8)


def open_session(otok, at):
    r = http("POST", "/api/club/open-session", token=otok, json_body={
        "clubId": CLUB_ID, "topicId": TOPIC, "sessionDate": at.strftime("%Y-%m-%d"), "sessionTime": at.strftime("%H:%M")})
    show("open-session %s" % at.strftime("%Y-%m-%d %H:%M"), j(r))
    return (r.get("data") or {}).get("activityId")


def buy(ptok, act, phone, label):
    http("POST", "/api/compliance/consents", token=ptok, json_body={
        "docType": "activity_host_data_sharing", "scene": "activity_signup", "eventType": "AGREE",
        "requestId": "r3d-consent-%s-%s" % (label, act)})
    ticket = q("SELECT id,price FROM oms_ticket WHERE owner_type=2 AND owner_id=%s AND del_flag=0 ORDER BY id LIMIT 1", (act,))[0]
    body = {"ownerType": 2, "ownerId": act, "ticketId": ticket[0], "tickets": [{"ticketId": ticket[0], "count": 1}],
            "realName": "E2E 玩家", "phone": phone}
    quote = http("POST", "/api/registration/quote", token=ptok, json_body=body)
    show("quote", j(quote, 500))
    body["quoteSign"] = quote["data"]["quoteSign"]
    body["requestId"] = "r3d-%s-%s-%d" % (label, act, int(time.time()))
    created = http("POST", "/api/registration/create", token=ptok, json_body=body)
    show("create", j(created))
    reg, reg_no = created["data"]["registrationId"], created["data"]["registrationNo"]
    cb = pay(reg_no, reg)
    return {"quote": quote.get("data"), "registrationId": reg, "callback": cb.get("_status"),
            "paid": readback("cms_registration(付后)", "SELECT id,owner_type,owner_id,payable_amount,fee_platform_rate,"
                             "payment_status,registration_status,refund_deadline FROM cms_registration WHERE id=%s", (reg,))}


def cancel(ptok, reg):
    rf = http("POST", "/api/registration/cancel-refund", token=ptok, form={"id": reg})
    show("cancel-refund", j(rf, 500))
    time.sleep(2)
    return {"response": {k: rf.get(k) for k in ("code", "msg", "data")},
            "registration": readback("cms_registration(退后)", "SELECT payment_status,registration_status,payable_amount "
                                     "FROM cms_registration WHERE id=%s", (reg,)),
            "refund_application": readback("refund_application", "SELECT refund_no,refund_amount,paid_amount,status,"
                                           "payout_status,payout_submission_state,payout_method,reason FROM refund_application "
                                           "WHERE source_type='registration' AND source_id=%s", (reg,)),
            "reconcile_job": readback("RefundReconcileTask 状态(1=暂停)", "SELECT job_id,invoke_target,status FROM sys_job "
                                      "WHERE invoke_target='refundReconcileTask.reconcile()'")}


def phase_tiers():
    otok, _ = app_token(OWNER_PHONE)
    ptok, _ = app_token(P9009)
    now = cst_now()
    step("T1 开场 3 天前自退:全额")
    a1 = open_session(otok, (now + datetime.timedelta(days=3)).replace(hour=10, minute=0))
    b1 = buy(ptok, a1, P9009, "t1")
    OUT["T1_more_than_24h"] = {"activityId": a1, "buy": b1, "cancel": cancel(ptok, b1["registrationId"])}

    step("T2 开场 24 小时内自退:50%,向下取整到分")
    at2 = (now + datetime.timedelta(hours=6)).replace(second=0, microsecond=0)
    at2 = at2.replace(minute=(at2.minute // 5) * 5)
    a2 = open_session(otok, at2)
    b2 = buy(ptok, a2, P9009, "t2")
    OUT["T2_within_24h"] = {"activityId": a2, "buy": b2, "cancel": cancel(ptok, b2["registrationId"])}

    step("T3 开场后自退:拒绝(夹具把场次开始时间挪到 1 小时前)")
    a3 = open_session(otok, (now + datetime.timedelta(days=2)).replace(hour=15, minute=0))
    b3 = buy(ptok, a3, P9009, "t3")
    ex("UPDATE cms_activity SET start_date = DATE_SUB(NOW(), INTERVAL 1 HOUR) WHERE id = %s", (a3,))
    ex("UPDATE oms_ticket SET start_time = DATE_SUB(NOW(), INTERVAL 1 HOUR) WHERE owner_type = 2 AND owner_id = %s", (a3,))
    OUT["T3_after_start"] = {"activityId": a3, "buy": b3, "cancel": cancel(ptok, b3["registrationId"])}


def phase_transfer():
    otok, _ = app_token(OWNER_PHONE)
    p4, _ = app_token(P9004)
    step("9004 申请加入俱乐部 7002,主理人通过")
    r = http("POST", "/api/club/join", token=p4, json_body={"id": CLUB_ID, "joinMessage": "R3D 证明"})
    show("join", j(r))
    r = http("POST", "/api/club/join-request/approve", token=otok, json_body={"clubId": CLUB_ID, "memberId": 9004,
                                                                              "reviewReason": "R3D"})
    show("approve", j(r))
    readback("club_member", "SELECT club_id,member_id,status FROM club_member WHERE club_id=%s ORDER BY member_id", (CLUB_ID,))
    # Fixture (same as round 2): the seeded owner lacks the role every real club owner carries.
    ex("UPDATE ums_member SET role = 'club' WHERE id = %s AND role <> 'club'", (OWNER_ID,))
    step("主理人转让:9008 → 9004(真实入口 /api/club/governance/owner/transfer)")
    r = http("POST", "/api/club/governance/owner/transfer", token=otok, json_body={
        "clubId": CLUB_ID, "targetMemberId": 9004, "reason": "R3D 证明:结算付给当前主理人",
        "requestId": "r3d-transfer-%d" % int(time.time())})
    show("transfer", j(r, 400))
    OUT["transfer"] = {"response": {k: r.get(k) for k in ("code", "msg", "data")},
                       "club": readback("club(转让后)", "SELECT id,member_id FROM club WHERE id=%s", (CLUB_ID,))}


def run_job(target):
    atok = admin_token()
    job_id, group = q("SELECT job_id,job_group FROM sys_job WHERE invoke_target=%s", (target,))[0]
    r = http("PUT", "/monitor/job/run", token=atok, json_body={"jobId": job_id, "jobGroup": group})
    show("run %s (%s)" % (target, job_id), j(r))
    time.sleep(6)
    log = q("SELECT status,LEFT(job_message,200),LEFT(exception_info,600) FROM sys_job_log WHERE invoke_target=%s "
            "ORDER BY job_log_id DESC LIMIT 1", (target,))
    show("job log", j(log, 900))
    return {"run": {k: r.get(k) for k in ("code", "msg")}, "log": log}


def phase_settle_activity(act):
    step("活动 %s 结算:夹具把场次挪到 8 天前,跑 settlementTask" % act)
    ex("UPDATE cms_activity SET start_date = DATE_SUB(NOW(), INTERVAL 8 DAY), end_date = DATE_SUB(NOW(), INTERVAL 8 DAY) "
       "WHERE id = %s", (act,))
    # Fixture: the fresh rig DB has no cms_setting row; settlement fails closed without a platform account.
    ex("INSERT IGNORE INTO cms_setting (id, platform_member_id, create_time) VALUES (1, 9001, NOW())")
    before = readback("activity", "SELECT id,club_id,member_id AS creator,is_settlement FROM cms_activity WHERE id=%s", (act,))
    principal = readback("club 当前主理人", "SELECT id,member_id FROM club WHERE id=%s", (CLUB_ID,))
    regs = readback("报名与退款", "SELECT r.id,r.payable_amount,r.payment_status,r.registration_status,"
                    "(SELECT SUM(refund_amount) FROM refund_application a WHERE a.source_type='registration' AND a.source_id=r.id) "
                    "FROM cms_registration r WHERE r.owner_type=2 AND r.owner_id=%s", (act,))
    job = run_job("settlementTask.run()")
    OUT["settle_activity"] = {
        "activity": before, "principal": principal, "registrations": regs, "job": job,
        "after": readback("activity(结算后)", "SELECT id,is_settlement FROM cms_activity WHERE id=%s", (act,)),
        "balance_detail": readback("ums_member_balance_detail eventType 3/7", "SELECT member_id,event_type,change_balance,"
                                   "LEFT(change_reason,40) FROM ums_member_balance_detail WHERE event_id=%s AND event_type IN (3,7) "
                                   "ORDER BY id", (act,)),
        "settlement_rule": readback("activity rule", "SELECT platform_commission_rate,merchant_rate,creator_rate FROM "
                                    "settlement_rule WHERE merchant_id=0 AND biz_type='activity' AND status=1")}


def phase_selfplay():
    p4 = app_token(P9009)[0]
    step("自玩通行证:玩家 9009 买俱乐部自有主题 %d 的通行证" % TOPIC)
    ticket = q("SELECT id FROM oms_ticket WHERE owner_type=1 AND owner_id=%s AND del_flag=0 ORDER BY id LIMIT 1", (TOPIC,))[0][0]
    body = {"ownerType": 1, "ownerId": TOPIC, "ticketId": ticket, "tickets": [{"ticketId": ticket, "count": 1}],
            "realName": "E2E 玩家", "phone": P9009}
    quote = http("POST", "/api/registration/quote", token=p4, json_body=body)
    show("quote", j(quote, 400))
    body["quoteSign"] = (quote.get("data") or {}).get("quoteSign")
    body["requestId"] = "r3d-selfplay-%d" % int(time.time())
    created = http("POST", "/api/registration/create", token=p4, json_body=body)
    show("create", j(created, 400))
    data = created.get("data") or {}
    reg, reg_no = data.get("registrationId"), data.get("registrationNo")
    cb = pay(reg_no, reg) if reg else {}
    OUT["selfplay"] = {"create": {k: created.get(k) for k in ("code", "msg", "data")}, "callback": cb.get("_status"),
                       "paid": readback("cms_registration", "SELECT id,owner_type,owner_id,payable_amount,fee_platform_rate,"
                                        "payment_status,registration_status,refund_deadline FROM cms_registration "
                                        "WHERE owner_type=1 AND owner_id=%s ORDER BY id", (TOPIC,))}


def phase_settle_topic():
    step("主题 %d 结算:夹具把主题结束时间与自玩单退款截止挪到过去,跑 settlementTask" % TOPIC)
    ex("UPDATE cms_topic SET end_date = DATE_SUB(NOW(), INTERVAL 8 DAY) WHERE id = %s", (TOPIC,))
    ex("UPDATE cms_registration SET refund_deadline = DATE_SUB(NOW(), INTERVAL 9 DAY) WHERE owner_type = 1 AND owner_id = %s "
       "AND payment_status = 2", (TOPIC,))
    readback("topic", "SELECT id,club_id,member_id,product_type,fee_platform_rate,is_settlement,end_date FROM cms_topic "
             "WHERE id=%s", (TOPIC,))
    readback("内容槽", "SELECT slot,entity_type,entity_id FROM product_role WHERE topic_id=%s AND status=1", (TOPIC,))
    readback("club 当前主理人", "SELECT id,member_id FROM club WHERE id=%s", (CLUB_ID,))
    job = run_job("settlementTask.run()")
    OUT["settle_topic"] = {"job": job,
                           "topic": readback("topic(结算后)", "SELECT is_settlement FROM cms_topic WHERE id=%s", (TOPIC,)),
                           "coop_settlement": readback("coop_settlement", "SELECT id,line_type,payee_type,merchant_id,amount,"
                                                       "status,payable_time,verified_sales FROM coop_settlement "
                                                       "WHERE topic_id=%s ORDER BY id", (TOPIC,))}


def phase_payout():
    step("payoutDueCoopSettlements:跑 coopSettlementPayoutTask")
    ex("UPDATE coop_settlement SET payable_time = DATE_SUB(NOW(), INTERVAL 1 MINUTE) WHERE topic_id = %s AND status = 0 "
       "AND payable_time > NOW()", (TOPIC,))
    job = run_job("coopSettlementPayoutTask.run()")
    OUT["payout"] = {"job": job,
                     "coop_settlement": readback("coop_settlement(放款后)", "SELECT id,line_type,merchant_id,amount,status "
                                                 "FROM coop_settlement WHERE topic_id=%s ORDER BY id", (TOPIC,)),
                     "balance_detail": readback("ums_member_balance_detail eventType 5", "SELECT member_id,event_type,"
                                                "change_balance,LEFT(change_reason,40) FROM ums_member_balance_detail WHERE "
                                                "event_type=5 ORDER BY id DESC LIMIT 5"),
                     "balances": readback("余额", "SELECT id,balance FROM ums_member WHERE id IN (9004,9008)")}


if __name__ == "__main__":
    phase = sys.argv[1]
    fn = {"tiers": phase_tiers, "transfer": phase_transfer, "selfplay": phase_selfplay,
          "settle_topic": phase_settle_topic, "payout": phase_payout}.get(phase)
    if phase == "settle_activity":
        phase_settle_activity(int(sys.argv[2]))
    else:
        fn()
    with open("round3d-%s.json" % phase, "w", encoding="utf-8") as f:
        json.dump(OUT, f, ensure_ascii=False, indent=1, default=str)
    print("\nRESULT " + json.dumps(OUT, ensure_ascii=False, default=str)[:3000])
