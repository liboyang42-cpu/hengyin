#!/usr/bin/env python3
"""pay-gate lane HTTP proof (walk-fix 2026-11-02). Runs ONLY against the throwaway cloud-walk rig
(127.0.0.1:8099, MySQL cy_e2e_51 on 127.0.0.1:3316). Uses the candidate's own scripts/e2e/chain_lib +
chain_player.store_trusted_payment_fact (the rig payment seam: verified fact + REAL /api/registration/callback).
payment_status is never written. Usage: $RIG_HOME/venv/bin/python proof_http.py <topicId>"""
import json, os, sys, datetime
from pathlib import Path
RIG_HOME = Path(os.environ.get("RIG_HOME", str(Path.home() / "walk-rig")))
CAND = Path(os.environ.get("CAND", str(RIG_HOME / "candidate")))
os.environ["PATH"] = "%s:%s" % (RIG_HOME / "work" / "bin", os.environ.get("PATH", ""))
for k, v in {"CHENGYIN_E2E_BASE": "http://127.0.0.1:8099", "CHENGYIN_E2E_DB_HOST": "127.0.0.1", "CHENGYIN_E2E_DB_PORT": "3316",
             "CHENGYIN_E2E_DB_USER": "root", "CHENGYIN_E2E_DB_PASSWORD": "", "CHENGYIN_E2E_DB_SCHEMA": "cy_e2e_51"}.items():
    os.environ.setdefault(k, v)
for k in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"):
    os.environ.pop(k, None)
sys.path.insert(0, str(CAND / "scripts" / "e2e"))
import io, contextlib  # noqa: E402
with contextlib.redirect_stdout(io.StringIO()):
    import chain_player as cp  # noqa: E402
from chain_lib import http, q, admin_token, app_token, step, show, readback  # noqa: E402

EXEC_CLUB_ID, EXEC_CLUB_PHONE = 7002, "13900000008"
PLAYER_PHONE = "13900000009"


def j(x, n=260):
    return json.dumps(x, ensure_ascii=False, default=str)[:n]


def buy_pay_refund(label, ptok, act, n):
    step("%s:玩家 报价 → 建单" % label)
    ticket = q("SELECT id,price FROM oms_ticket WHERE owner_type=2 AND owner_id=%s AND del_flag=0 ORDER BY id LIMIT 1", (act,))[0]
    body = {"ownerType": 2, "ownerId": act, "ticketId": ticket[0], "tickets": [{"ticketId": ticket[0], "count": 1}],
            "realName": "E2E 玩家二号", "phone": PLAYER_PHONE}
    quote = http("POST", "/api/registration/quote", token=ptok, json_body=body)
    show("quote", j(quote))
    if not quote.get("data"):
        return {"quote": quote}
    body["quoteSign"] = quote["data"]["quoteSign"]
    body["requestId"] = "paygate-%s-%d-%s" % (label, n, act)
    created = http("POST", "/api/registration/create", token=ptok, json_body=body)
    show("create", j(created))
    if not created.get("data"):
        return {"quote": quote, "create": created}
    reg, reg_no = created["data"]["registrationId"], created["data"]["registrationNo"]
    amount = q("SELECT COALESCE(wechat_payment_amount,payable_amount) FROM cms_registration WHERE id=%s", (reg,))[0][0]
    fen = int(round(float(amount) * 100))
    step("%s:支付成功走真实回调 /api/registration/callback(%s 分)" % (label, fen))
    txn = "PAYGATE-TXN-%d" % reg
    with contextlib.redirect_stdout(io.StringIO()):
        fact = cp.store_trusted_payment_fact(reg_no, txn, fen)
    cb = http("POST", "/api/registration/callback", json_body=dict(fact, **{
        "out_trade_no": reg_no, "transaction_id": txn, "trade_state": "SUCCESS", "total": fen}))
    show("callback", j(cb))
    paid = readback("cms_registration", "SELECT id,owner_type,owner_id,payable_amount,fee_platform_rate,payment_status,"
                    "registration_status,transaction_id FROM cms_registration WHERE id=%s", (reg,))
    sd = readback("settlement_detail", "SELECT source_type,receiver_type,receiver_id,entry_kind,gross_amount,commission_amount,"
                  "settlement_amount,status FROM settlement_detail WHERE source_id=%s ORDER BY id", (reg,))
    step("%s:玩家 取消并退款 /api/registration/cancel-refund" % label)
    rf = http("POST", "/api/registration/cancel-refund", token=ptok, form={"id": reg})
    show("cancel-refund", j(rf, 400))
    after = readback("cms_registration(退后)", "SELECT payment_status,registration_status FROM cms_registration WHERE id=%s", (reg,))
    ra = readback("refund_application", "SELECT refund_no,refund_amount,paid_amount,status,payout_status,payout_method FROM refund_application "
                  "WHERE source_type='registration' AND source_id=%s", (reg,))
    sd2 = readback("settlement_detail(退后)", "SELECT source_type,receiver_type,receiver_id,entry_kind,gross_amount,commission_amount,"
                   "settlement_amount,status FROM settlement_detail WHERE source_id=%s ORDER BY id", (reg,))
    return {"quote": quote.get("data"), "registrationId": reg, "callbackStatus": cb.get("_status"), "paid": paid,
            "settlement_detail": sd, "refund": {k: rf.get(k) for k in ("code", "msg", "data")}, "after": after,
            "refund_application": ra, "settlement_detail_after": sd2}


def open_session(etok, tid, days, hhmm):
    d = q("SELECT DATE_FORMAT(DATE_ADD(NOW(), INTERVAL 8 HOUR) + INTERVAL %s DAY, '%%Y-%%m-%%d')", (days,))[0][0]
    r = http("POST", "/api/club/open-session", token=etok, json_body={
        "clubId": EXEC_CLUB_ID, "topicId": tid, "sessionDate": d, "sessionTime": hhmm})
    show("open-session %s %s" % (d, hhmm), j(r))
    return (r.get("data") or {}).get("activityId")


def main(tid):
    out = {"topicId": tid}
    atok = admin_token()
    etok, _ = app_token(EXEC_CLUB_PHONE)
    ptok, _ = app_token(PLAYER_PHONE)
    http("POST", "/api/compliance/consents", token=ptok, json_body={
        "docType": "activity_host_data_sharing", "scene": "activity_signup", "eventType": "AGREE",
        "requestId": "paygate-consent-%d" % tid})

    step("A 俱乐部自有主题:内容槽改判为 CLUB(%d)" % EXEC_CLUB_ID)
    r = http("PUT", "/business/topic/%d/content-slot" % tid, token=atok,
             json_body={"entityType": "CLUB", "entityId": EXEC_CLUB_ID})
    show("content-slot", j(r))
    out["A_slot"] = readback("product_role", "SELECT slot,entity_type,entity_id,status FROM product_role "
                             "WHERE topic_id=%s AND status=1 ORDER BY id", (tid,))
    act = open_session(etok, tid, int(os.environ.get("DAY_A", "3")), "10:00")
    out["A_activity"] = act
    out["A"] = buy_pay_refund("A-club", ptok, act, 1) if act else {"error": "open-session failed"}

    step("B 平台自营主题:内容槽改判为 PLATFORM(对照,应与改动前一致)")
    r = http("PUT", "/business/topic/%d/content-slot" % tid, token=atok, json_body={"entityType": "PLATFORM"})
    show("content-slot", j(r))
    out["B_slot"] = readback("product_role", "SELECT slot,entity_type,entity_id,status FROM product_role "
                             "WHERE topic_id=%s AND status=1 ORDER BY id", (tid,))
    act = open_session(etok, tid, int(os.environ.get("DAY_B", "4")), "10:00")
    out["B_activity"] = act
    out["B"] = buy_pay_refund("B-platform", ptok, act, 2) if act else {"error": "open-session failed"}
    print("\nRESULT " + json.dumps(out, ensure_ascii=False, default=str))


if __name__ == "__main__":
    main(int(sys.argv[1]))
