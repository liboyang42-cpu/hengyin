#!/usr/bin/env python3
"""[f-money rig] 俱乐部会费（CLM，平台商户号收款）真 HTTP 资金链路：
主理人开启会员制并保存一个收费方案 → 玩家续费下单 → 桩通道写一条「已验签」原始支付事实（同 chain_player 的做法）
→ 真实回调入口 POST /api/registration/callback → 回读库里所有与这笔钱有关的行，输出归一化 JSON（去掉自增 id / 时间 / 哈希），
用于 master 与 launch 两个 jar 的逐行对比。只连 rig 测试库（chain_lib 拒连生产主机）。
用法：CAND=<候选工作树> $RIG_HOME/venv/bin/python club_dues_flow.py <out.json>"""
import json, os, sys, uuid
from pathlib import Path
RIG_HOME = Path(os.environ.get("RIG_HOME", str(Path.home() / "walk-rig")))
CAND = Path(os.environ.get("CAND", str(RIG_HOME / "candidate")))
for k, v in {"CHENGYIN_E2E_BASE": "http://127.0.0.1:8099", "CHENGYIN_E2E_DB_HOST": "127.0.0.1", "CHENGYIN_E2E_DB_PORT": "3316",
             "CHENGYIN_E2E_DB_USER": "root", "CHENGYIN_E2E_DB_PASSWORD": "", "CHENGYIN_E2E_DB_SCHEMA": "cy_e2e_51"}.items():
    os.environ.setdefault(k, v)
for k in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"):
    os.environ.pop(k, None)
sys.path.insert(0, str(CAND / "scripts" / "e2e"))
import io, contextlib  # noqa: E402
with contextlib.redirect_stdout(io.StringIO()):
    import chain_player as cp  # noqa: E402
from chain_lib import http, q, app_token  # noqa: E402

OWNER_PHONE, CLUB_ID = "13900000003", 7001
PLAYER_PHONE = "13900000009"
log = []


def call(label, method, path, token, body):
    r = http(method, path, token=token, json_body=body)
    log.append({"step": label, "code": r.get("code"), "msg": r.get("msg"), "status": r.get("_status")})
    return r


def rows(sql, args=()):
    return [[str(x) for x in r] for r in q(sql, args)]


def main(out):
    otok, _ = app_token(OWNER_PHONE)
    ptok, pmember = app_token(PLAYER_PHONE)
    s = call("setting", "POST", "/api/club/membership/setting", otok, {"clubId": CLUB_ID})
    ver = (s.get("data") or {}).get("version")
    call("setting/update", "POST", "/api/club/membership/setting/update", otok,
         {"clubId": CLUB_ID, "enabled": True, "version": ver, "requestId": "fm-set-" + uuid.uuid4().hex[:12]})
    p = call("plan/save", "POST", "/api/club/membership/plan/save", otok,
             {"clubId": CLUB_ID, "name": "月度会员", "durationDays": 30, "priceFen": 1990, "benefits": ["活动优先报名"],
              "graceDays": 3, "pastDueDays": 7, "exitPolicy": "END_IMMEDIATELY", "refundPolicy": "MANUAL_REVIEW",
              "status": "ACTIVE", "requestId": "fm-plan-" + uuid.uuid4().hex[:12]})
    plan_id = (p.get("data") or {}).get("id") or (p.get("data") or {}).get("planId")
    if plan_id is None:
        got = rows("SELECT id FROM club_membership_plan WHERE club_id=%s ORDER BY id DESC LIMIT 1", (CLUB_ID,))
        plan_id = int(got[0][0]) if got else None
    r = call("renew", "POST", "/api/club/membership/renew", ptok,
             {"clubId": CLUB_ID, "planId": plan_id, "idempotencyKey": "fm-renew-1", "requestId": "fm-renew-" + uuid.uuid4().hex[:12]})
    inv = rows("SELECT invoice_no,amount_fen,status FROM club_membership_invoice WHERE club_id=%s ORDER BY id DESC LIMIT 1", (CLUB_ID,)) \
        if q("SHOW TABLES LIKE 'club_membership_invoice'") else []
    paid = None
    if inv:
        no, fen = inv[0][0], int(inv[0][1])
        with contextlib.redirect_stdout(io.StringIO()):
            fact = cp.store_trusted_payment_fact(no, "RIG-FM-TXN-" + no[-8:], fen)
            paid = http("POST", "/api/registration/callback", json_body=dict(fact, **{
                "out_trade_no": no, "transaction_id": "RIG-FM-TXN-" + no[-8:], "trade_state": "SUCCESS", "total": fen}))
        log.append({"step": "callback", "status": paid.get("_status"), "raw": str(paid.get("_raw", ""))[:120]})
    snap = {"log": log, "renewData": {k: v for k, v in (r.get("data") or {}).items() if k in ("status", "amountFen", "payable")}}
    for t, cols in [("club_membership_invoice", "status,amount_fen,currency,plan_id,member_id,club_id,payment_status,transaction_id"),
                    ("club_membership", "club_id,member_id,status,plan_id"),
                    ("wx_verified_payment_fact", "fact_kind,mchid,trade_type,currency,total_fen,amount_fen,verification_outcome,original_payment_state"),
                    ("wx_pay_order_intent", "mchid,trade_type,currency,total_fen,attempt_no"),
                    ("ums_member_balance_detail", "member_id,type,amount,source_type"),
                    ("ecom_order_seller", "*"), ("ecom_combine_order", "*"), ("ecom_profit_share_order", "*"),
                    ("ecom_supplier_payable", "*"), ("platform_pass_order", "*")]:
        if not q("SHOW TABLES LIKE %s", (t,)):
            snap[t] = "TABLE_ABSENT"
            continue
        have = {c[0] for c in q("SHOW COLUMNS FROM `%s`" % t)}
        use = [c for c in cols.split(",") if c == "*" or c in have]
        sel = "COUNT(*)" if use == ["*"] else ",".join(use)
        snap[t] = rows("SELECT %s FROM `%s` ORDER BY 1" % (sel, t))
    Path(out).write_text(json.dumps(snap, ensure_ascii=False, indent=1))
    print(json.dumps(snap, ensure_ascii=False)[:3000])


if __name__ == "__main__":
    main(sys.argv[1])
