#!/usr/bin/env python3
"""免费报名取消验收(小程序验收清单 CL-17):真 HTTP 调用 + 真 SQL 回读,补两道保护。

  ① 先保留异常结果:每次 HTTP 的原始响应先追加写进证据文件并 fsync,然后才判定;
     业务拒绝或回包未知(断连/超时/5xx)一律停在原地 —— 不重试、不清理、不补写。
  ② 事务结算后回读:取消请求返回(或回包丢失)后,以库行为准轮询到终态
     (报名不再计入已报名、资格全部失效),再读主理人名册与玩家订单两条独立视图,
     库、名册、订单三方一致才算通过。

另:旧测试会话先复核有效期 —— 每个令牌先打 /api/userInfo,会员号与预期一致才继续。
令牌只从环境变量读,不落证据、不打印。

只走 chain_lib 的 loopback 后端与 cy_e2e_* 测试库(它拒连生产主机)。全程只有一次写:
POST /api/registration/cancel-refund,由操作者在获准的验收窗口里显式执行。

用法(验收窗口内):
  CHENGYIN_E2E_PLAYER_TOKEN=… CHENGYIN_E2E_ORGANIZER_TOKEN=… CHENGYIN_E2E_DB_*=… \
  python3 -m scripts.e2e.free_cancel_acceptance --registration-id R --club-id C \
      --player-member-id P --organizer-member-id O --evidence <私有目录>/free-cancel.jsonl
"""
import argparse, json, os, re, sys, time

REGISTRATION_SQL = ("SELECT id, member_id, owner_type, owner_id, payable_amount, point_used, "
                    "payment_status, registration_status, verification_status "
                    "FROM cms_registration WHERE id = %s")
ENTITLEMENT_SQL = "SELECT id, status FROM registration_entitlement WHERE registration_id = %s ORDER BY id"
# 与名册 selectPaidRoster、countSignedUpRegistrations 同一口径:已支付且报名成功才占名额。
SIGNED_UP_SQL = ("SELECT COUNT(*) FROM cms_registration WHERE owner_type = 2 AND owner_id = %s "
                 "AND payment_status = 2 AND registration_status = 2")
SECRET_KEY = re.compile(r"token|authorization|password|secret|cookie", re.I)


def redact(value):
    if isinstance(value, dict):
        return {k: ("<redacted>" if SECRET_KEY.search(str(k)) else redact(v)) for k, v in value.items()}
    if isinstance(value, (list, tuple)):
        return [redact(v) for v in value]
    return value


class Evidence:
    """追加写证据:先落盘再判定。已存在的文件不覆盖,防止把上一轮的异常结果冲掉。"""

    def __init__(self, path):
        if os.path.exists(path):
            raise SystemExit("证据文件已存在,换一个新路径,旧结果原样保留: %s" % path)
        self.path, self.seq = path, 0

    def record(self, stage, data):
        self.seq += 1
        line = json.dumps({"seq": self.seq, "stage": stage, "at": time.strftime("%Y-%m-%dT%H:%M:%S%z"),
                           "data": redact(data)}, ensure_ascii=False, default=str)
        with open(self.path, "a", encoding="utf-8") as f:
            f.write(line + "\n")
            f.flush()
            os.fsync(f.fileno())


def classify(resp):
    """OK / REJECTED(服务端明确拒绝,未必没写)/ UNKNOWN(回包丢失,写入结果未知)。"""
    status = resp.get("_status")
    if status == -1 or status is None or status >= 500:
        return "UNKNOWN"
    if status == 200 and str(resp.get("code")) == "200":
        return "OK"
    return "REJECTED"


def session_owner(resp):
    if classify(resp) != "OK":
        return None
    user = resp.get("appUser") or {}
    return str(user.get("userId")) if user.get("userId") is not None else None


def is_active(row):
    """报名行是否仍占名额(payment_status=2 且 registration_status=2)。"""
    return bool(row) and row[6] == 2 and row[7] == 2


def is_free_session_order(row):
    return bool(row) and row[2] == 2 and float(row[4] or 0) == 0 and int(row[5] or 0) == 0


def roster_members(resp):
    data = resp.get("data") or {}
    return {str(r.get("memberId")) for key in ("registered", "arrived", "noShow") for r in (data.get(key) or [])}


def order_row(resp, registration_id):
    for row in ((resp.get("data") or {}).get("rows") or []):
        if str(row.get("id")) == str(registration_id):
            return row
    return None


def settled(snap):
    return not is_active(snap["registration"]) and all(s == 2 for _, s in snap["entitlements"])


def judge(action, before, after, player):
    """返回 (结论, 原因列表)。三方一致 + 名额释放才算 PASS;回包丢失但库已落定单列 UNKNOWN_COMMITTED。"""
    reasons = []
    if not is_active(after["registration"]):
        if player in after["roster"]:
            reasons.append("报名已失效但主理人名册仍列出该玩家")
        if after["signed_up"] != before["signed_up"] - 1:
            reasons.append("已报名计数未减 1: %s → %s" % (before["signed_up"], after["signed_up"]))
        if any(s != 2 for _, s in after["entitlements"]):
            reasons.append("资格行未全部失效: %s" % (after["entitlements"],))
        order = after["order"]
        if order is None:
            reasons.append("玩家订单列表读不到这单")
        elif order.get("canRequestRefund") is True or (order.get("paymentStatus") == 2 and order.get("registrationStatus") == 2):
            reasons.append("玩家订单仍显示为有效报名")
    else:
        reasons.append("库里报名仍有效")
        if player not in after["roster"]:
            reasons.append("报名仍有效但主理人名册不再列出该玩家")
    if action == "OK":
        return ("PASS" if not reasons else "FAIL"), reasons
    if action == "UNKNOWN" and not reasons:
        return "UNKNOWN_COMMITTED", ["取消回包丢失,但库与两条视图一致显示已取消;须核对用户侧提示"]
    return "FAIL", ["取消请求结果=%s" % action] + reasons


def snapshot(q, http, args, organizer_token, player_token):
    reg = q(REGISTRATION_SQL, (args.registration_id,))
    row = reg[0] if reg else None
    activity = row[3] if row else None
    roster = http("POST", "/api/club/event-ops/roster", token=organizer_token,
                  json_body={"clubId": args.club_id, "activityId": activity})
    orders = http("POST", "/api/registration/list", token=player_token,
                  form={"pageNum": 1, "pageSize": 50, "owner_type": 3, "status": "0"})
    return {"registration": row, "entitlements": [tuple(r) for r in q(ENTITLEMENT_SQL, (args.registration_id,))],
            "signed_up": (q(SIGNED_UP_SQL, (activity,)) or [(None,)])[0][0],
            "roster": roster_members(roster), "order": order_row(orders, args.registration_id),
            "_raw": {"roster": roster, "orders": orders}}


def run(args, http, q, evidence, environ, sleep=time.sleep):
    tokens = {"player": environ.get("CHENGYIN_E2E_PLAYER_TOKEN", ""),
              "organizer": environ.get("CHENGYIN_E2E_ORGANIZER_TOKEN", "")}
    expected = {"player": str(args.player_member_id), "organizer": str(args.organizer_member_id)}
    for who in ("player", "organizer"):
        if not tokens[who]:
            evidence.record("session." + who, {"verdict": "MISSING"})
            return "STOP_SESSION", ["缺少 %s 会话" % who]
        resp = http("POST", "/api/userInfo", token=tokens[who])
        owner = session_owner(resp)
        evidence.record("session." + who, {"classified": classify(resp), "userId": owner, "expected": expected[who]})
        if owner != expected[who]:
            return "STOP_SESSION", ["%s 会话失效或身份不符(期望 %s,实际 %s)" % (who, expected[who], owner)]

    before = snapshot(q, http, args, tokens["organizer"], tokens["player"])
    evidence.record("before", before)
    row = before["registration"]
    if not is_free_session_order(row) or str(row[1]) != expected["player"]:
        return "STOP_PRECONDITION", ["目标不是该玩家的免费场次报名"]
    if not is_active(row) or expected["player"] not in before["roster"]:
        return "STOP_PRECONDITION", ["取消前报名不处于有效状态或未出现在名册里"]

    resp = http("POST", "/api/registration/cancel-refund", token=tokens["player"], form={"id": args.registration_id})
    evidence.record("cancel", resp)          # ① 先落原始回包,再判定
    action = classify(resp)

    deadline = time.monotonic() + args.settle_seconds
    after = snapshot(q, http, args, tokens["organizer"], tokens["player"])
    while not settled(after) and action != "REJECTED" and time.monotonic() < deadline:
        sleep(1)                             # ② 以库行为准等到终态,不拿接口回包当事实
        after = snapshot(q, http, args, tokens["organizer"], tokens["player"])
    evidence.record("after", after)
    verdict, reasons = judge(action, before, after, expected["player"])
    evidence.record("verdict", {"verdict": verdict, "reasons": reasons, "action": action})
    return verdict, reasons


def main(argv=None):
    p = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    p.add_argument("--registration-id", type=int, required=True)
    p.add_argument("--club-id", type=int, required=True)
    p.add_argument("--player-member-id", type=int, required=True)
    p.add_argument("--organizer-member-id", type=int, required=True)
    p.add_argument("--evidence", required=True)
    p.add_argument("--settle-seconds", type=int, default=30)
    args = p.parse_args(argv)
    from scripts.e2e import chain_lib  # 导入时即校验 cy_e2e_* 库与 loopback 后端
    verdict, reasons = run(args, chain_lib.http, chain_lib.q, Evidence(args.evidence), os.environ)
    print(json.dumps({"verdict": verdict, "reasons": reasons, "evidence": args.evidence}, ensure_ascii=False))
    return 0 if verdict == "PASS" else 1


if __name__ == "__main__":
    sys.exit(main())
