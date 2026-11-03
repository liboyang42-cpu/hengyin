#!/usr/bin/env python3
"""pay-gate Round 4 proof: 100/50/0 tiers on passes, over real HTTP with database read-back (throwaway rig only).

Self-play passes: theme R4_TOPIC (club 7002's own classic theme, built by setup_club_theme.py).
Explore passes (③): theme 990014 「E2E 探店日一期」 from the chains, session activity R4_EXPLORE_ACT.
payment_status is written only by the real /api/registration/callback after chain_player.store_trusted_payment_fact.
Fixture writes (test DB only, each printed): date moves on cms_topic.start_date / oms_ticket.start_time BEFORE purchase
(so the purchase itself freezes the anchor), one move of a frozen anchor into the past for "after start", and two
rows rewritten to the pre-2026-11-03 shape (no snapshot for ②; EXPLORE_END_100_0 for ③) to show old promises are kept.
Usage: python proof_round4.py selfplay|explore   (appends JSON to proof/round4-<phase>.json)"""
import datetime, json, os, sys, time
from rig_env import http, q, app_token, step, show, readback, j, pay

P9009 = "13900000009"
OUT = {}


def ex(sql, args=()):
    import chain_lib
    n = chain_lib.ex(sql, args)
    show("FIXTURE SQL", (sql % tuple(args)) if args else sql)
    return n


def cst_now():
    return datetime.datetime.utcnow() + datetime.timedelta(hours=8)


def order_view(tok, reg):
    r = http("POST", "/api/registration/info", token=tok, form={"id": reg})
    d = r.get("data") or {}
    view = {"refundInfo": d.get("refundInfo"), "refundDeadlineDisplay": d.get("refundDeadlineDisplay")}
    show("/api/registration/info 退款展示", j(view, 600))
    return view


def snap(reg):
    return readback("cms_registration 快照", "SELECT id,owner_type,purchase_kind,payable_amount,point_used,"
                    "refund_policy_code,refund_policy_version,refund_deadline,payment_status,registration_status "
                    "FROM cms_registration WHERE id=%s", (reg,))


def cancel(tok, reg):
    rf = http("POST", "/api/registration/cancel-refund", token=tok, form={"id": reg})
    show("cancel-refund", j(rf, 500))
    time.sleep(2)
    return {"response": {k: rf.get(k) for k in ("code", "msg", "data")},
            "registration": readback("cms_registration(退后)", "SELECT payment_status,registration_status,payable_amount,"
                                     "points_returned FROM cms_registration WHERE id=%s", (reg,)),
            "refund_application": readback("refund_application", "SELECT refund_no,refund_amount,paid_amount,status,"
                                           "payout_status,reason FROM refund_application WHERE source_type='registration' "
                                           "AND source_id=%s", (reg,))}


def buy_pass(tok, topic, label):
    http("POST", "/api/compliance/consents", token=tok, json_body={
        "docType": "activity_host_data_sharing", "scene": "activity_signup", "eventType": "AGREE",
        "requestId": "r4-consent-%s-%d" % (label, int(time.time() * 1000))})
    body = {"ownerType": 1, "ownerId": topic, "realName": "E2E 玩家", "phone": P9009, "isUsePoint": 0,
            "requestId": "r4-%s-%d" % (label, int(time.time() * 1000))}
    created = http("POST", "/api/registration/create", token=tok, json_body=body)
    show("create 自玩通行证", j(created, 400))
    d = created.get("data") or {}
    reg, reg_no = d.get("registrationId"), d.get("registrationNo")
    cb = pay(reg_no, reg)
    return reg, cb.get("_status")


def phase_selfplay():
    topic = int(os.environ["R4_TOPIC"])
    tok = app_token(P9009)[0]
    now = cst_now()
    readback("主题", "SELECT id,club_id,product_type,self_play_price,start_date,end_date FROM cms_topic WHERE id=%s", (topic,))
    cases = [("S1_more_than_24h", 3, None), ("S2_within_24h", 1, None), ("S3_after_start", 1, "past"),
             ("S4_legacy_no_snapshot_within_24h", 1, "legacy")]
    only = os.environ.get("R4_ONLY")
    if only:
        cases = [c for c in cases if c[0].startswith(only)]
        tok = app_token(os.environ.get("R4_PHONE", P9009))[0]
    for name, days, mode in cases:
        step("%s:主题开始日 = 今天+%d(下单前夹具)" % (name, days))
        ex("UPDATE cms_topic SET start_date = %s WHERE id = %s",
           ((now + datetime.timedelta(days=days)).strftime("%Y-%m-%d 09:00:00"), topic))
        reg, cb = buy_pass(tok, topic, name)
        frozen = snap(reg)
        if mode == "past":
            ex("UPDATE cms_registration SET refund_deadline = DATE_SUB(NOW(), INTERVAL 1 HOUR) WHERE id = %s", (reg,))
        if mode == "legacy":
            ex("UPDATE cms_registration SET refund_policy_code = NULL, refund_policy_version = NULL, refund_deadline = NULL "
               "WHERE id = %s", (reg,))
        OUT[name] = {"registrationId": reg, "callback": cb, "frozen": frozen, "view": order_view(tok, reg),
                     "cancel": cancel(tok, reg)}


def phase_explore():
    act = int(os.environ["R4_EXPLORE_ACT"])
    tok = app_token(P9009)[0]
    http("POST", "/api/compliance/consents", token=tok, json_body={
        "docType": "activity_host_data_sharing", "scene": "activity_signup", "eventType": "AGREE",
        "requestId": "r4-consent-%s" % act})
    ticket = q("SELECT id FROM oms_ticket WHERE owner_type=2 AND owner_id=%s AND del_flag=0 AND price>0 ORDER BY id LIMIT 1",
               (act,))[0][0]
    readback("票", "SELECT id,name,price,mode,start_time,end_time,refund_rule FROM oms_ticket WHERE id=%s", (ticket,))
    cases = [("E1b_more_than_24h", 72, None), ("E2_within_24h", 5, None), ("E3_legacy_explore_end_after_start", 5, "legacy")]
    for name, hours, mode in cases:
        step("%s:票开始时间 = 现在+%d 小时(下单前夹具)" % (name, hours))
        # Rig MySQL runs in UTC while the app reads DATETIME as CST: +8 h turns NOW() into CST "now".
        ex("UPDATE oms_ticket SET start_time = DATE_ADD(NOW(), INTERVAL %s HOUR) WHERE id = %s", (hours + 8, ticket))
        # single-ticket body, as pages/activity/baoming sends it
        body = {"ownerType": 2, "ownerId": act, "ticketId": ticket,
                "realName": "E2E 玩家", "phone": P9009, "isUsePoint": 0}
        quote = http("POST", "/api/registration/quote", token=tok, json_body=body)
        qd = quote.get("data") or {}
        show("quote", j({k: quote.get(k) for k in ("code", "msg")}))
        show("quote 退款字段", j({k: qd.get(k) for k in ("payAmount", "refundableNow", "refundPercentNow", "refundDeadline")}))
        body["quoteSign"] = qd.get("quoteSign")
        body["requestId"] = "r4-%s-%d" % (name, int(time.time() * 1000))
        created = http("POST", "/api/registration/create", token=tok, json_body=body)
        show("create", j(created, 300))
        d = created.get("data") or {}
        reg = d.get("registrationId")
        cb = pay(d.get("registrationNo"), reg)
        frozen = snap(reg)
        if mode == "legacy":
            ex("UPDATE cms_registration SET refund_policy_code = 'EXPLORE_END_100_0', refund_deadline = "
               "(SELECT end_time FROM oms_ticket WHERE id = %s) WHERE id = %s", (ticket, reg))
            ex("UPDATE oms_ticket SET start_time = DATE_SUB(NOW(), INTERVAL 1 HOUR) WHERE id = %s", (ticket,))
        OUT[name] = {"registrationId": reg, "quote": {k: qd.get(k) for k in ("payAmount", "refundableNow",
                     "refundPercentNow", "refundDeadline")}, "callback": cb.get("_status"), "frozen": frozen,
                     "view": order_view(tok, reg), "cancel": cancel(tok, reg)}


if __name__ == "__main__":
    phase = sys.argv[1]
    {"selfplay": phase_selfplay, "explore": phase_explore}[phase]()
    p = os.path.join(os.path.dirname(os.path.abspath(__file__)), "round4-%s.json" % phase)
    with open(p, "a") as f:
        f.write(json.dumps(OUT, ensure_ascii=False, default=str, indent=1) + "\n")
