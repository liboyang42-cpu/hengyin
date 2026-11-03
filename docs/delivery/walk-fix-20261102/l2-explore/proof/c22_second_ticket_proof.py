#!/usr/bin/env python3
"""l2-explore C-22 证明(只对走查 rig 的一次性库):同一玩家买同一主题的第二场,真实 HTTP + 库回读。

用法(rig 已 up、chains.py 已把 990014 带到 SELLING):
  python3 c22_second_ticket_proof.py buy <tag> <minutes_from_now>   # 执行俱乐部开一场 + 9004 报名/支付
  python3 c22_second_ticket_proof.py readback                        # 9004 在 990014 上的有效票
夹具如实报告:支付复用 chain_player.store_trusted_payment_fact 扮演已验签渠道,再走真实 /api/registration/callback;
不写 payment_status。
"""
import sys, json, pathlib
ROOT = pathlib.Path(__file__).resolve().parents[5]
sys.path.insert(0, str(ROOT / "scripts" / "e2e"))
from chain_lib import http, q, step, show, readback, app_token  # noqa: E402
from chain_player import store_trusted_payment_fact  # noqa: E402

TID, EXEC_CLUB_ID, EXEC_CLUB_PHONE = 990014, 7002, "13900000008"
PLAYER_PHONE, PLAYER = "13900000004", 9004


def buy(tag, minutes):
    etok, _ = app_token(EXEC_CLUB_PHONE)
    ptok, _ = app_token(PLAYER_PHONE)
    day, at = q("SELECT DATE_FORMAT(DATE_ADD(NOW(), INTERVAL 8 HOUR) + INTERVAL %s MINUTE, '%%Y-%%m-%%d'), "
                "DATE_FORMAT(DATE_ADD(NOW(), INTERVAL 8 HOUR) + INTERVAL %s MINUTE, '%%H:%%i')", (minutes, minutes))[0]
    step("场次 %s:执行俱乐部开一场 %s %s" % (tag, day, at))
    r = http("POST", "/api/club/open-session", token=etok,
             json_body={"clubId": EXEC_CLUB_ID, "topicId": TID, "sessionDate": day, "sessionTime": at})
    show("open-session", json.dumps(r, ensure_ascii=False)[:160])
    act = (r.get("data") or {}).get("activityId")
    if not act: return 1
    show("9004 入会", json.dumps(http("POST", "/api/club/join", token=ptok,
         json_body={"id": EXEC_CLUB_ID, "joinMessage": "周末想去探店"}), ensure_ascii=False)[:120])
    http("POST", "/api/compliance/consents", token=ptok, json_body={
        "docType": "activity_host_data_sharing", "scene": "activity_signup", "eventType": "AGREE",
        "requestId": "c22-consent-%s" % act})
    ticket = q("SELECT id FROM oms_ticket WHERE owner_type=2 AND owner_id=%s ORDER BY id LIMIT 1", (act,))[0][0]
    body = {"ownerType": 2, "ownerId": act, "ticketId": ticket, "tickets": [{"ticketId": ticket, "count": 1}],
            "realName": "走查玩家", "phone": PLAYER_PHONE}
    quote = http("POST", "/api/registration/quote", token=ptok, json_body=body)
    show("quote", json.dumps(quote, ensure_ascii=False)[:200])
    if not (quote.get("data") or {}).get("quoteSign"): return 2
    body["quoteSign"] = quote["data"]["quoteSign"]; body["requestId"] = "c22-reg-%s" % act
    created = http("POST", "/api/registration/create", token=ptok, json_body=body)
    show("create", json.dumps(created, ensure_ascii=False)[:200])
    if not (created.get("data") or {}).get("registrationId"): return 3
    reg, reg_no = created["data"]["registrationId"], created["data"]["registrationNo"]
    total = int(round(float(quote["data"]["payAmount"]) * 100))
    fact = store_trusted_payment_fact(reg_no, "C22-TXN-%d" % reg, total)
    cb = http("POST", "/api/registration/callback", json_body=dict(fact, **{
        "out_trade_no": reg_no, "transaction_id": "C22-TXN-%d" % reg, "trade_state": "SUCCESS", "total": total}))
    show("callback", "status=%s" % cb.get("_status"))
    print("\nACTIVITY_ID=%s REGISTRATION_ID=%s" % (act, reg))
    return 0


def final_readback():
    readback("9004 在 990014 上的报名单",
             "SELECT r.id, r.owner_id AS activity_id, a.start_date, r.registration_status, r.payment_status "
             "FROM cms_registration r JOIN cms_activity a ON r.owner_type=2 AND r.owner_id=a.id "
             "WHERE r.member_id=%s AND a.topic_id=%s ORDER BY r.id", (PLAYER, TID))
    return 0


if __name__ == "__main__":
    if sys.argv[1] == "buy": sys.exit(buy(sys.argv[2], int(sys.argv[3])))
    if sys.argv[1] == "readback": sys.exit(final_readback())
