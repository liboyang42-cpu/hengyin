#!/usr/bin/env python3
"""l2-explore 证明脚本(只对走查 rig 的一次性库):#707 + P-03 + P-13,全程真实 HTTP + 库回读。

用法(rig 已 up、chains.py 已把 990014 带到 SELLING):
  CHENGYIN_E2E_DB_PASSWORD=... python3 explore_707_proof.py open            # 新开一场(现在+3 分钟)+ 9004 入会/报名/支付
  python3 explore_707_proof.py redeem <act> <reg> <n>                         # 第 n 家(1..4):玩家打卡 + 商家核销 + 回读
  python3 explore_707_proof.py readback <act> <reg>                           # 完成奖励回读
夹具(写测试库,如实报告):
  - cms_topic.complete_rule_json 写成编辑器每次存盘的格式 {"nodeCompletion":{"mode":"ALL"}}(#707 要证的就是这个格式)。
  - 支付:复用 chain_player.store_trusted_payment_fact 扮演已验签渠道,再走真实 /api/registration/callback;不写 payment_status。
"""
import sys, json, pathlib, time
ROOT = pathlib.Path(__file__).resolve().parents[5]
sys.path.insert(0, str(ROOT / "scripts" / "e2e"))
from chain_lib import http, q, ex, app_token, step, show, readback  # noqa: E402
from chain_player import store_trusted_payment_fact, MERCHANT_PHONE_BY_MEMBER  # noqa: E402

TID, EXEC_CLUB_ID, EXEC_CLUB_PHONE = 990014, 7002, "13900000008"
PLAYER_PHONE, PLAYER = "13900000004", 9004
RULE = '{"nodeCompletion":{"mode":"ALL"}}'


def open_session():
    step("夹具:主题完成规则写成编辑器格式 %s" % RULE)
    ex("UPDATE cms_topic SET complete_rule_json=%s WHERE id=%s", (RULE, TID))
    readback("规则", "SELECT id,product_type,complete_rule_json FROM cms_topic WHERE id=%s", (TID,))
    etok, _ = app_token(EXEC_CLUB_PHONE)
    ptok, _ = app_token(PLAYER_PHONE)
    today, at = q("SELECT DATE_FORMAT(DATE_ADD(NOW(), INTERVAL 8 HOUR) + INTERVAL 3 MINUTE, '%%Y-%%m-%%d'), "
                  "DATE_FORMAT(DATE_ADD(NOW(), INTERVAL 8 HOUR) + INTERVAL 3 MINUTE, '%%H:%%i')")[0]
    r = http("POST", "/api/club/open-session", token=etok,
             json_body={"clubId": EXEC_CLUB_ID, "topicId": TID, "sessionDate": today, "sessionTime": at})
    show("open-session", json.dumps(r, ensure_ascii=False)[:160])
    act = (r.get("data") or {}).get("activityId")
    if not act: return 1
    show("9004 入会", json.dumps(http("POST", "/api/club/join", token=ptok, json_body={"id": EXEC_CLUB_ID, "joinMessage": "周末想去探店"}), ensure_ascii=False)[:120])
    http("POST", "/api/compliance/consents", token=ptok, json_body={
        "docType": "activity_host_data_sharing", "scene": "activity_signup", "eventType": "AGREE", "requestId": "l2-consent-%s" % act})
    ticket = q("SELECT id FROM oms_ticket WHERE owner_type=2 AND owner_id=%s ORDER BY id LIMIT 1", (act,))[0][0]
    body = {"ownerType": 2, "ownerId": act, "ticketId": ticket, "tickets": [{"ticketId": ticket, "count": 1}],
            "realName": "走查玩家", "phone": PLAYER_PHONE}
    quote = http("POST", "/api/registration/quote", token=ptok, json_body=body)
    show("quote", json.dumps(quote, ensure_ascii=False)[:160])
    body["quoteSign"] = quote["data"]["quoteSign"]; body["requestId"] = "l2-reg-%s" % act
    created = http("POST", "/api/registration/create", token=ptok, json_body=body)
    show("create", json.dumps(created, ensure_ascii=False)[:160])
    reg, reg_no = created["data"]["registrationId"], created["data"]["registrationNo"]
    total = int(round(float(quote["data"]["payAmount"]) * 100))
    fact = store_trusted_payment_fact(reg_no, "L2-TXN-%d" % reg, total)
    cb = http("POST", "/api/registration/callback", json_body=dict(fact, **{
        "out_trade_no": reg_no, "transaction_id": "L2-TXN-%d" % reg, "trade_state": "SUCCESS", "total": total}))
    show("callback", "status=%s" % cb.get("_status"))
    readback("报名单", "SELECT id,payment_status,verification_status,purchase_kind FROM cms_registration WHERE id=%s", (reg,))
    readback("权益", "SELECT chapter_id,status FROM registration_entitlement WHERE registration_id=%s ORDER BY chapter_id", (reg,))
    print("\nACTIVITY_ID=%s REGISTRATION_ID=%s" % (act, reg))
    return 0


def wait_start(act):
    for _ in range(80):
        if q("SELECT DATE_ADD(NOW(), INTERVAL 8 HOUR) >= start_date FROM cms_activity WHERE id=%s", (act,))[0][0]:
            return True
        time.sleep(5)
    return False


def redeem(act, reg, n):
    ptok, _ = app_token(PLAYER_PHONE)
    if not wait_start(act): print("场次未开始"); return 1
    nodes = q("SELECT id,chapter_id,checkin_code,merchant_member_id,name FROM cms_topic_node "
              "WHERE topic_id=%s AND merchant_member_id IS NOT NULL ORDER BY chapter_id", (TID,))
    nid, chapter_id, code, merchant, name = nodes[n - 1]
    step("第 %d 家 %s:玩家扫店码 → 商家扫玩家动态码核销" % (n, name))
    show("checkin", json.dumps(http("POST", "/api/play/checkin", token=ptok,
         form={"activityId": act, "code": code, "topicId": TID, "nodeId": nid}), ensure_ascii=False)[:160])
    mtok, _ = app_token(MERCHANT_PHONE_BY_MEMBER[merchant])
    dyn = http("POST", "/api/verify/dyncode/issue", token=ptok, form={"registrationId": reg, "type": "activity"})
    show("scan_qr_code", json.dumps(http("POST", "/api/registration/scan_qr_code", token=mtok,
         form={"type": "activity", "code": dyn["data"]["code"]}), ensure_ascii=False)[:200])
    readback("报名单", "SELECT id,payment_status,verification_status,first_successful_scan_at FROM cms_registration WHERE id=%s", (reg,))
    readback("权益", "SELECT chapter_id,status FROM registration_entitlement WHERE registration_id=%s ORDER BY chapter_id", (reg,))
    lists(reg)
    return 0


def lists(reg):
    ptok, _ = app_token(PLAYER_PHONE)
    lst = http("POST", "/api/registration/list", token=ptok, form={"owner_type": 2})
    data = (lst.get("data") or {}).get("rows") or []
    rows = [r for r in data if r.get("id") == reg]
    show("list owner_type=2 → exploreProgress", json.dumps(rows[0].get("exploreProgress") if rows else "本单不在列表", ensure_ascii=False))
    for status in ("2", "3"):
        o = http("POST", "/api/registration/list", token=ptok, form={"owner_type": 3, "status": status})
        data = (o.get("data") or {}).get("rows") or []
        show("我的订单 status=%s 含本单" % status, str(any(r.get("id") == reg for r in data)))
    return 0


def final_readback(act, reg):
    step("完成奖励回读")
    ptok, _ = app_token(PLAYER_PHONE)
    show("explore-completion", json.dumps(http("POST", "/api/registration/explore-completion", token=ptok, form={"id": reg}), ensure_ascii=False)[:400])
    readback("redemption", "SELECT chapter_id,merchant_id,status FROM redemption WHERE registration_id=%s ORDER BY chapter_id", (reg,))
    readback("通关勋章", "SELECT id,badge_code,source_id FROM player_badge WHERE member_id=%s AND source_id=%s", (PLAYER, TID))
    readback("通关积分", "SELECT id,change_points,event_type,event_id FROM ums_member_points_detail WHERE member_id=%s AND event_type=13", (PLAYER,))
    readback("通关经验", "SELECT * FROM topic_completion_xp_award WHERE member_id=%s AND topic_id=%s", (PLAYER, TID))
    return 0


if __name__ == "__main__":
    cmd = sys.argv[1]
    if cmd == "open": sys.exit(open_session())
    if cmd == "redeem": sys.exit(redeem(int(sys.argv[2]), int(sys.argv[3]), int(sys.argv[4])))
    if cmd == "lists": sys.exit(lists(int(sys.argv[2])))
    if cmd == "readback": sys.exit(final_readback(int(sys.argv[2]), int(sys.argv[3])))
