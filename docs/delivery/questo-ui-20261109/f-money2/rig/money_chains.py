#!/usr/bin/env python3
"""[f-money2 rig] 四类订单的真实 HTTP 资金链：下单 → 支付回调 → 结算 / T+14 → 退款 → 对账。

前置：rig 已起（up.sh），chains.py sale_close 已把一期「E2E 探店日一期」带到 SELLING，
install-channel.sh 已编入通道模拟器并按 RIG_SWITCHES=off|on 重启应用。

每一步都走产品真实入口（小程序 /api/*、后台 /business/*、/monitor/job/run 触发真实定时任务）。
只有三类夹具写库（rig 测试库，带 [FIXTURE] 标记写进证据）：
  1) 已验签支付事实：直连通道没有商户私钥，同 chain_player.store_trusted_payment_fact；
     电商收付通单不用它（通道模拟器回包、产品自己查单写事实）；
  2) 时间压缩：把场次 / 支付时间整体拨到 15 天前，让 T+14 结算任务按真实谓词选中；
  3) 产品配置：通行证套餐上架、俱乐部会费功能闸（与 f-money 同口径）。
每个流程前后对所有资金表做全量快照差分，并附通道模拟器记下的每笔资金移动。

用法：CAND=<候选工作树> $RIG_HOME/venv/bin/python money_chains.py <off|on> <out.json>
"""
import contextlib, datetime, decimal, io, json, os, sys, time, uuid
from pathlib import Path

RIG_HOME = Path(os.environ.get("RIG_HOME", str(Path.home() / "walk-rig")))
CAND = Path(os.environ.get("CAND", str(Path.home() / "cand")))
for k, v in {"CHENGYIN_E2E_BASE": "http://127.0.0.1:8099", "CHENGYIN_E2E_DB_HOST": "127.0.0.1",
             "CHENGYIN_E2E_DB_PORT": "3316", "CHENGYIN_E2E_DB_USER": "root", "CHENGYIN_E2E_DB_PASSWORD": "",
             "CHENGYIN_E2E_DB_SCHEMA": "cy_e2e_51"}.items():
    os.environ.setdefault(k, v)
for k in ("HTTP_PROXY", "HTTPS_PROXY", "http_proxy", "https_proxy"):
    os.environ.pop(k, None)
sys.path.insert(0, str(CAND / "scripts" / "e2e"))
with contextlib.redirect_stdout(io.StringIO()):
    import chain_player as cp  # noqa: E402
from chain_lib import http, q, ex, admin_token, app_token  # noqa: E402

MODE = sys.argv[1] if len(sys.argv) > 1 else "off"
OUT = Path(sys.argv[2] if len(sys.argv) > 2 else str(RIG_HOME / "out" / ("money-%s.json" % MODE)))
LEDGER = RIG_HOME / "out" / "channel-ledger.jsonl"
CLUB_ID, OWNER_PHONE = 7001, "13900000003"
PLAYER_PHONE, PLAYER_MEMBER = "13900000004", 9004       # 非俱乐部成员：自玩票原价、场次票需入会故用 9009
MEMBER_PHONE, MEMBER_ID = "13900000009", 9009
MONEY_TABLES = [
    "cms_registration", "cms_registration_payment_fact", "wx_pay_order_intent", "wx_verified_payment_fact",
    "wx_verified_payment_fact_rejection", "wx_payment_callback_inbox", "refund_application",
    "ums_member_balance_detail", "ums_member_settlement_debt", "settlement_detail", "settlement_batch",
    "settlement_claim", "cms_activity_settlement_hold", "coop_settlement", "coop_settlement_detail",
    "coop_settlement_original_component", "coop_settlement_refund_receipt", "merchant_payable", "club_payable",
    "topic_edition_funds", "club_membership_invoice", "club_membership_term", "club_membership_refund_review",
    "platform_pass_order", "platform_pass_membership", "platform_pass_claim",
    "ecom_sub_merchant", "ecom_order_seller", "ecom_refund", "ecom_profit_share_order", "ecom_profit_share_line",
    "ecom_profit_share_return", "ecom_supplier_payable", "ecom_combine_order", "ecom_combine_sub_order",
    "activity_cancel_manual_refund_case", "member_cash_withdraw",
]
SKIP_COLS = {"create_time", "update_time", "create_by", "update_by"}
evidence = {"mode": MODE, "started": datetime.datetime.now().isoformat(timespec="seconds"), "flows": []}


def jsonable(v):
    if isinstance(v, (datetime.datetime, datetime.date)):
        return v.isoformat(sep=" ") if isinstance(v, datetime.datetime) else v.isoformat()
    if isinstance(v, decimal.Decimal):
        return str(v)
    if isinstance(v, (bytes, bytearray)):
        return v.hex()
    return v


def snapshot():
    snap = {}
    for t in MONEY_TABLES:
        if not q("SHOW TABLES LIKE %s", (t,)):
            continue
        cols = [c[0] for c in q("SHOW COLUMNS FROM `%s`" % t)]
        key = cols[0]
        snap[t] = {str(r[0]): {c: jsonable(v) for c, v in zip(cols, r)}
                   for r in q("SELECT * FROM `%s` ORDER BY `%s`" % (t, key))}
    return snap


def diff(before, after):
    out = {}
    for t, rows in after.items():
        old = before.get(t, {})
        changes = []
        for k, row in rows.items():
            if k not in old:
                changes.append({"op": "INSERT", "row": {c: v for c, v in row.items() if c not in SKIP_COLS}})
            elif row != old[k]:
                delta = {c: [old[k].get(c), v] for c, v in row.items() if old[k].get(c) != v and c not in SKIP_COLS}
                if delta:
                    changes.append({"op": "UPDATE", "id": k, "changed": delta})
        if changes:
            out[t] = changes
    return out


def ledger_lines():
    return LEDGER.read_text().splitlines() if LEDGER.exists() else []


class Flow:
    def __init__(self, name):
        self.name, self.steps, self.notes = name, [], []

    def __enter__(self):
        self.before, self.ledger0 = snapshot(), len(ledger_lines())
        print("\n=== %s (%s)" % (self.name, MODE))
        return self

    def step(self, label, resp=None, **extra):
        rec = {"step": label}
        if isinstance(resp, dict):
            rec.update({"code": resp.get("code"), "msg": (resp.get("msg") or "")[:160], "status": resp.get("_status")})
            if resp.get("_raw") is not None:
                rec["raw"] = str(resp.get("_raw"))[:200]
            data = resp.get("data")
            if data is not None:
                rec["data"] = json.dumps(data, ensure_ascii=False, default=str)[:600]
        rec.update(extra)
        self.steps.append(rec)
        print("  - %-34s %s" % (label, json.dumps({k: v for k, v in rec.items() if k != "step"}, ensure_ascii=False, default=str)[:220]))
        return resp

    def note(self, text):
        self.notes.append(text)
        print("  * " + text)

    def __exit__(self, et, ev, tb):
        if ev is not None:
            self.note("流程中断：%s: %s" % (type(ev).__name__, ev))
        moves = [json.loads(x) for x in ledger_lines()[self.ledger0:]]
        evidence["flows"].append({"flow": self.name, "steps": self.steps, "notes": self.notes,
                                  "db_changes": diff(self.before, snapshot()), "channel_moves": moves})
        return True  # 一个流程断了不影响下一个流程


def run_job(atok, target):
    rows = q("SELECT job_id,job_group FROM sys_job WHERE invoke_target=%s", (target,))
    if not rows:
        return {"code": 404, "msg": "job not registered: " + target}
    r = http("PUT", "/monitor/job/run", token=atok, json_body={"jobId": rows[0][0], "jobGroup": rows[0][1]})
    time.sleep(3)
    return r


def pay(flow, reg_no, fen, label="pay"):
    """卖家收款单：通道模拟器收到「付款人已付」通知，产品查原单核对后写事实；否则夹具写已验签事实 + 直连回调。"""
    seller = q("SELECT sub_mchid FROM ecom_order_seller WHERE out_trade_no=%s", (reg_no,))
    if seller:
        r = http("POST", "/api/registration/ecommerce-callback",
                 json_body={"sub_mchid": seller[0][0], "out_trade_no": reg_no})
        return flow.step(label + "（电商收付通通知）", r, sub_mchid=seller[0][0])
    with contextlib.redirect_stdout(io.StringIO()):
        txn = "RIG-TXN-" + reg_no[-10:]
        fact = cp.store_trusted_payment_fact(reg_no, txn, fen)
    flow.note("[FIXTURE] 直连商户号已验签支付事实 id=%s（无商户私钥，同 chain_player）" % fact["verified_fact_id"])
    r = http("POST", "/api/registration/callback", json_body=dict(fact, **{
        "out_trade_no": reg_no, "transaction_id": txn, "trade_state": "SUCCESS", "total": fen}))
    return flow.step(label + "（直连回调）", r)


def refund_notify(flow, reg_id):
    """模拟微信退款通知：把退款单号投递到真实退款回调入口（电商单走电商退款回调）；通知体只有单号，事实由产品回查。"""
    ecom = {r[0] for r in q("SELECT out_refund_no FROM ecom_refund WHERE out_trade_no=(SELECT registration_no FROM "
                             "cms_registration WHERE id=%s)", (reg_id,))}
    for no in sorted(ecom):
        flow.step("电商退款通知 %s" % no, http("POST", "/api/registration/ecommerce-refund-callback",
                                             json_body={"out_refund_no": no}))
    for (no,) in q("SELECT refund_no FROM refund_application WHERE source_type='registration' AND source_id=%s "
                   "AND refund_no IS NOT NULL ORDER BY id", (reg_id,)):
        if no in ecom:
            continue
        flow.step("退款通知 %s" % no, http("POST", "/api/registration/refund-callback", json_body={"out_refund_no": no}))


def participant(tok, phone, name):
    return http("POST", "/api/user/address/action", token=tok, form={
        "fullName": name, "mobilePhone": phone, "isDefault": "1", "requestId": "fm2-participant-" + phone})


def buy(flow, tok, phone, name, body, label):
    flow.step("参与人 " + name, participant(tok, phone, name))
    flow.step("同意报名信息共享", http("POST", "/api/compliance/consents", token=tok, json_body={
        "docType": "activity_host_data_sharing", "scene": "activity_signup", "eventType": "AGREE",
        "requestId": "fm2-consent-" + uuid.uuid4().hex[:10]}))
    body = dict(body, realName=name, phone=phone)
    quote = flow.step(label + " 报价", http("POST", "/api/registration/quote", token=tok, json_body=body))
    if not quote.get("data"):
        raise RuntimeError("报价失败")
    body["quoteSign"] = quote["data"]["quoteSign"]
    body["requestId"] = "fm2-%s-%s" % (MODE, uuid.uuid4().hex[:12])
    created = flow.step(label + " 建单", http("POST", "/api/registration/create", token=tok, json_body=body))
    if not created.get("data"):
        raise RuntimeError("建单失败")
    d = created["data"]
    fen = int(round(float(d.get("payableAmount") or 0) * 100))
    return d["registrationId"], d["registrationNo"], fen


def selling_topic():
    rows = q("SELECT id FROM cms_topic WHERE name='E2E 探店日一期' AND club_id=%s AND lifecycle=3 AND del_flag=0 "
             "ORDER BY id DESC LIMIT 1", (CLUB_ID,))
    if not rows:
        raise SystemExit("没有 SELLING 的链路主题：先跑 chains.py sale_close")
    return rows[0][0]


def onboard_club(flow, atok):
    view = flow.step("俱乐部收款账户（后台查看）", http("GET", "/business/ecommerce-payout/sub-merchant?ownerType=CLUB&ownerId=%d" % CLUB_ID, token=atok))
    row = q("SELECT id,applyment_state,sub_mchid FROM ecom_sub_merchant WHERE owner_type='CLUB' AND owner_id=%s", (CLUB_ID,))
    if not row:
        flow.step("登记二级商户", http("POST", "/business/ecommerce-payout/sub-merchant/register?ownerType=CLUB&ownerId=%d" % CLUB_ID, token=atok))
        row = q("SELECT id,applyment_state,sub_mchid FROM ecom_sub_merchant WHERE owner_type='CLUB' AND owner_id=%s", (CLUB_ID,))
    if row and row[0][1] != "FINISH":
        flow.step("提交进件（通道模拟器受理）", http("POST", "/business/ecommerce-payout/sub-merchant/%d/submit" % row[0][0],
                  token=atok, json_body={"organization_type": "2", "merchant_shortname": "E2E 城瘾跑团"}))
        flow.step("刷新进件状态", http("POST", "/business/ecommerce-payout/sub-merchant/%d/refresh" % row[0][0], token=atok))
    return q("SELECT applyment_state,sub_mchid FROM ecom_sub_merchant WHERE owner_type='CLUB' AND owner_id=%s", (CLUB_ID,))


def shift_registration_time(reg_id, days=15):
    ex("UPDATE cms_registration SET payment_time=DATE_SUB(payment_time, INTERVAL %s DAY), "
       "create_time=DATE_SUB(create_time, INTERVAL %s DAY) WHERE id=%s", (days, days, reg_id))


def main():
    atok = admin_token()
    otok, _ = app_token(OWNER_PHONE)
    ptok, _ = app_token(PLAYER_PHONE)
    mtok, _ = app_token(MEMBER_PHONE)
    tid = selling_topic()
    evidence["topic_id"] = tid
    evidence["switches"] = http("GET", "/business/ecommerce-payout/switches", token=atok).get("data")
    print("topic", tid, "switches", evidence["switches"])

    # ---------------- 主题门票（自玩通行证，owner_type=1） ----------------
    theme = tid
    for leg in ("退款", "结算"):
        with Flow("主题门票-%s" % leg) as f:
            if leg == "退款":
                r = http("PUT", "/business/topic", token=atok, json_body={"id": tid, "selfPlay": 1, "selfPlayPrice": 39.9})
                f.step("后台开自玩票 ¥39.90", r)
                if (q("SELECT self_play FROM cms_topic WHERE id=%s", (tid,)) or ((0,),))[0][0] != 1:
                    ex("UPDATE cms_topic SET self_play=1,self_play_price=39.90 WHERE id=%s", (tid,))
                    f.note("[FIXTURE] 后台入口拒改非官方主题（产品规则），直接写 cms_topic.self_play/self_play_price=39.90")
            if MODE == "on":
                f.step("俱乐部二级商户进件", None, result=[list(map(str, r)) for r in onboard_club(f, atok)])
            reg, no, fen = buy(f, ptok, PLAYER_PHONE, "E2E 玩家小城", {"ownerType": 1, "ownerId": theme}, "自玩票")
            f.step("订单", None, registration=[reg, no, fen])
            pay(f, no, fen)
            if leg == "退款":
                f.step("玩家取消并退款", http("POST", "/api/registration/cancel-refund", token=ptok, form={"id": reg}))
                refund_notify(f, reg)
                f.step("退款对账任务", run_job(atok, "refundReconcileTask.reconcile()"))
            else:
                shift_registration_time(reg)
                f.note("[FIXTURE] 支付时间整体拨到 15 天前（T+14 已到）；主题仍在售，不改档期")
                f.step("结算任务", run_job(atok, "settlementTask.run()"))
                if MODE == "on":
                    f.step("计划分账", http("POST", "/business/ecommerce-payout/profit-share/topic/%d/plan" % theme, token=atok))
                    f.step("提交分账", http("POST", "/business/ecommerce-payout/profit-share/submit", token=atok))
                    f.step("同步分账", http("POST", "/business/ecommerce-payout/profit-share/sync", token=atok))
                    f.step("分账任务（T+14 定时）", run_job(atok, "ecommercePayoutTask.shareAndRetry()"))
                f.step("结算后主理人清退", http("POST", "/api/registration/cancel-by-owner", token=otok, form={"id": reg}))
                refund_notify(f, reg)
                f.step("退款对账任务", run_job(atok, "refundReconcileTask.reconcile()"))

    # ---------------- 俱乐部场次票（owner_type=2） ----------------
    for leg in ("退款", "结算"):
        with Flow("场次票-%s" % leg) as f:
            day = (datetime.date.today() + datetime.timedelta(days=3 if leg == "退款" else 4)).isoformat()
            r = f.step("主理人开场次 %s" % day, http("POST", "/api/club/open-session", token=otok, json_body={
                "clubId": CLUB_ID, "topicId": tid, "sessionDate": day, "sessionTime": "10:%02d" % (11 if MODE == "on" else 7)}))
            act = (r.get("data") or {}).get("activityId")
            if not act:
                raise RuntimeError("开场次失败")
            ticket = q("SELECT id FROM oms_ticket WHERE owner_type=2 AND owner_id=%s ORDER BY id LIMIT 1", (act,))[0][0]
            reg, no, fen = buy(f, mtok, MEMBER_PHONE, "E2E 玩家二号",
                               {"ownerType": 2, "ownerId": act, "ticketId": ticket, "tickets": [{"ticketId": ticket, "count": 1}]},
                               "场次票")
            f.step("订单", None, registration=[reg, no, fen], activity=act)
            pay(f, no, fen)
            if leg == "退款":
                f.step("玩家开场前取消并退款", http("POST", "/api/registration/cancel-refund", token=mtok, form={"id": reg}))
                refund_notify(f, reg)
                f.step("退款对账任务", run_job(atok, "refundReconcileTask.reconcile()"))
            else:
                ex("UPDATE cms_activity SET start_date=DATE_SUB(start_date, INTERVAL 20 DAY), "
                   "end_date=DATE_SUB(end_date, INTERVAL 20 DAY) WHERE id=%s", (act,))
                ex("UPDATE cms_registration SET refund_deadline=DATE_SUB(NOW(), INTERVAL 19 DAY) WHERE id=%s", (reg,))
                shift_registration_time(reg, 21)
                f.note("[FIXTURE] 场次整体拨到 20 天前（结束 + 14 天已过，退款截止已过）")
                f.step("结算任务（T+14）", run_job(atok, "settlementTask.run()"))
                f.step("结算后主理人清退", http("POST", "/api/registration/cancel-by-owner", token=otok, form={"id": reg}))
                refund_notify(f, reg)
                f.step("退款对账任务", run_job(atok, "refundReconcileTask.reconcile()"))

    # ---------------- 俱乐部会费 ----------------
    with Flow("俱乐部会费") as f:
        ex("INSERT INTO sys_config (config_name,config_key,config_value,config_type,create_by,create_time,remark) "
           "SELECT '俱乐部付费会员','feature.flag.clubMembership','true','N','rig',NOW(),'f-money2 rig fixture' "
           "WHERE NOT EXISTS (SELECT 1 FROM sys_config WHERE config_key='feature.flag.clubMembership')")
        ex("UPDATE sys_config SET config_value='true' WHERE config_key='feature.flag.clubMembership'")
        f.note("[FIXTURE] 既有功能闸 feature.flag.clubMembership=true（上线前就有的闸，两种开关一致）")
        f.step("刷新配置缓存", http("DELETE", "/system/config/refreshCache", token=atok))
        s = f.step("会费设置", http("POST", "/api/club/membership/setting", token=otok, json_body={"clubId": CLUB_ID}))
        f.step("开启会员制", http("POST", "/api/club/membership/setting/update", token=otok, json_body={
            "clubId": CLUB_ID, "enabled": True, "version": (s.get("data") or {}).get("version"),
            "requestId": "fm2-set-" + uuid.uuid4().hex[:10]}))
        p = f.step("保存收费方案 ¥19.90/30天", http("POST", "/api/club/membership/plan/save", token=otok, json_body={
            "clubId": CLUB_ID, "name": "月度会员", "durationDays": 30, "priceFen": 1990, "benefits": ["活动优先报名"],
            "graceDays": 3, "pastDueDays": 7, "exitPolicy": "END_IMMEDIATELY", "refundPolicy": "MANUAL_REVIEW",
            "status": "ACTIVE", "requestId": "fm2-plan-" + uuid.uuid4().hex[:10]}))
        plan_id = (p.get("data") or {}).get("id") or (q("SELECT id FROM club_membership_plan WHERE club_id=%s ORDER BY id DESC LIMIT 1", (CLUB_ID,)) or ((None,),))[0][0]
        r = f.step("会员续费下单", http("POST", "/api/club/membership/renew", token=mtok, json_body={
            "clubId": CLUB_ID, "planId": plan_id, "idempotencyKey": "fm2-renew-" + MODE,
            "requestId": "fm2-renew-" + uuid.uuid4().hex[:10]}))
        inv = q("SELECT id,invoice_no,amount_fen FROM club_membership_invoice WHERE club_id=%s AND member_id=%s ORDER BY id DESC LIMIT 1",
                (CLUB_ID, MEMBER_ID))
        if not inv:
            raise RuntimeError("没有会费账单")
        inv_id, inv_no, inv_fen = inv[0][0], inv[0][1], int(inv[0][2])
        pay(f, inv_no, inv_fen, "会费支付")
        f.step("会员申请退款", http("POST", "/api/club/membership/refund/review", token=mtok, json_body={
            "clubId": CLUB_ID, "id": inv_id, "reason": "E2E 退款", "requestId": "fm2-rf-" + uuid.uuid4().hex[:10]}))
        f.step("财务对账（主理人）", http("POST", "/api/club/membership/finance/invoices", token=otok, json_body={"clubId": CLUB_ID}))

    # ---------------- 通行证 JSAPI ----------------
    with Flow("通行证JSAPI") as f:
        f.step("通行证总览", http("GET", "/api/platform-pass", token=ptok))
        plans = q("SELECT plan_code,price_fen,status FROM platform_pass_plan ORDER BY price_fen")
        f.step("套餐", None, plans=[list(map(str, x)) for x in plans])
        if MODE == "on" and plans:
            cols = {c[0] for c in q("SHOW COLUMNS FROM platform_pass_plan")}
            if "status" in cols:
                ex("UPDATE platform_pass_plan SET status=1 WHERE plan_code=%s", (plans[0][0],))
                f.note("[FIXTURE] 套餐 %s 上架（迁移自带、默认未上架）" % plans[0][0])
        o = f.step("下单（JSAPI）", http("POST", "/api/platform-pass/orders", token=ptok, json_body={
            "planCode": plans[0][0] if plans else "MONTH", "channel": "JSAPI", "requestId": "fm2-pp-" + uuid.uuid4().hex[:10]}))
        no = ((o.get("data") or {}).get("orderNo")) if isinstance(o.get("data"), dict) else None
        if not no:
            f.note("通行证未能下单（开关全关时这是预期）")
        else:
            f.step("拉起支付", http("POST", "/api/platform-pass/orders/%s/pay" % no, token=ptok, json_body={}))
            fen = int(q("SELECT price_fen FROM platform_pass_order WHERE order_no=%s", (no,))[0][0])
            pay(f, no, fen, "通行证支付")
            f.step("后台退款", http("POST", "/business/platformPass/orders/%s/refund?reason=E2E" % no, token=atok))
            rno = q("SELECT refund_no FROM platform_pass_order WHERE order_no=%s", (no,))
            if rno and rno[0][0]:
                f.step("退款通知", http("POST", "/api/registration/refund-callback", json_body={"out_refund_no": rno[0][0]}))
            f.step("订单回读", http("GET", "/api/platform-pass/orders/%s" % no, token=ptok))

    # ---------------- 对账 ----------------
    with Flow("对账") as f:
        f.step("退款对账任务", run_job(atok, "refundReconcileTask.reconcile()"))
        facts = q("SELECT fact_kind,mchid,business_out_trade_no,total_fen,amount_fen FROM wx_verified_payment_fact ORDER BY id")
        by_mch = {}
        for kind, mch, no, total, amount in facts:
            b = by_mch.setdefault(mch, {"PAYMENT": 0, "REFUND": 0})
            b[kind] += int(amount)
        f.step("按收款商户号汇总（已验签事实）", None, totals=by_mch)
        evidence["fact_totals"] = by_mch

    evidence["finished"] = datetime.datetime.now().isoformat(timespec="seconds")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(json.dumps(evidence, ensure_ascii=False, indent=1, default=str))
    print("\nwrote", OUT)


if __name__ == "__main__":
    main()
