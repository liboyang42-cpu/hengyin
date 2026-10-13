#!/usr/bin/env python3
"""5-1 资金段：质量确认 → FINAL 六量 → 应付批次 → 双人确认/付款登记。

用法: python3 scripts/e2e/chain_finance.py <topicId>
前置: chain_player.py 已完成一笔本地桩支付和四章核销。
"""
import decimal, hashlib, json, pathlib, sys
from zoneinfo import ZoneInfo

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from chain_lib import admin_token, db, http, q, readback, show, step

CLUB_ID = 7002
QUALITY_DIMENSIONS = ("DELIVERY_SAFETY", "PLAYER_EXPERIENCE", "CONTENT_REPORT")


def backend_token(username):
    result = http("POST", "/login", json_body={"username": username, "password": "admin123"})
    if not result.get("token"):
        raise RuntimeError("后台测试账号登录失败: %s" % username)
    return result["token"]


def plain(value):
    if value is None:
        return "null"
    text = format(value, "f")
    return text.rstrip("0").rstrip(".") if "." in text else text


def compress_frozen_window(topic_id):
    """本地时间压缩夹具；连时间与指纹一起原子改写，不削弱产品的防篡改校验。"""
    conn = db()
    conn.autocommit(False)
    cur = conn.cursor()
    try:
        cur.execute("SELECT DATE_SUB(NOW(),INTERVAL 2 DAY),DATE_SUB(NOW(),INTERVAL 1 DAY)")
        fulfillment_end, evidence_due = cur.fetchone()
        cur.execute("""SELECT topic_id,executing_club_id,fixed_compensation,extra_hour_rate,
                              quality_dimension_amount,quality_dimensions,extra_approver_user_id,
                              headcount_rate,headcount_cap
                         FROM club_edition_compensation_terms WHERE topic_id=%s FOR UPDATE""", (topic_id,))
        row = cur.fetchone()
        if not row:
            raise RuntimeError("缺少冻结补偿条款")
        shanghai = ZoneInfo("Asia/Shanghai")
        millis = lambda value: str(int(value.replace(tzinfo=shanghai).timestamp() * 1000))
        parts = [str(row[0]), str(row[1]), plain(row[2]), plain(row[3]), plain(row[4]), str(row[5]),
                 millis(evidence_due), millis(fulfillment_end), str(row[6])]
        if (row[7] is not None and row[7] != decimal.Decimal("0")) or row[8] is not None:
            parts = ["\x1f".join(parts), plain(row[7]), plain(row[8])]
        config_hash = hashlib.sha256("\x1f".join(parts).encode()).hexdigest()
        cur.execute("""UPDATE club_edition_compensation_terms
                          SET evidence_due_at=%s,fulfillment_end_at=%s,config_hash=%s
                        WHERE topic_id=%s""",
                    (evidence_due, fulfillment_end, config_hash, topic_id))
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        cur.close(); conn.close()


def main(topic_id):
    admin = admin_token()

    step("平台逐项确认三维质量事实")
    for dimension in QUALITY_DIMENSIONS:
        result = http("POST", "/business/club-compensation/%s/quality/confirm" % topic_id,
                      token=admin, json_body={"clubId": CLUB_ID, "dimension": dimension,
                                              "pass": True, "reason": "E2E 履约验收通过"})
        show(dimension, json.dumps(result, ensure_ascii=False)[:160])
        if result.get("code") != 200:
            return 1

    step("时间压缩夹具:将冻结履约窗整体拨到过去,原子重算同口径 SHA-256 指纹")
    compress_frozen_window(topic_id)
    readback("冻结时窗/指纹", "SELECT fulfillment_end_at,evidence_due_at,config_hash "
             "FROM club_edition_compensation_terms WHERE topic_id=%s", (topic_id,))

    step("平台 FINAL:六量资金快照 + 俱乐部四分量应付同事务落库")
    result = http("POST", "/business/edition-final/%s" % topic_id, token=admin)
    show("edition-final", json.dumps(result, ensure_ascii=False)[:260])
    if result.get("code") != 200:
        return 1
    funds = readback("FINAL 六量", "SELECT snapshot_kind,n_cash,g_cash,u_in,g_earned,u_out,u_pending,r_refund "
                     "FROM topic_edition_funds WHERE topic_id=%s AND is_current=1", (topic_id,))
    if len(funds) != 1 or funds[0][0] != "FINAL" or funds[0][1] != 1 or funds[0][2] != funds[0][4]:
        print("   ✗ FINAL 六量或现金守恒不成立")
        return 1
    readback("俱乐部应付", "SELECT component,entry_type,amount,batch_id FROM club_payable "
             "WHERE topic_id=%s ORDER BY id", (topic_id,))

    step("财务建批并确认 → 运营登记本地付款回执(双账号职责分离)")
    finance = backend_token("e2e_finance")
    operator = backend_token("e2e_operator")
    created = http("POST", "/business/edition-final/%s/batch" % topic_id, token=finance,
                   json_body={"clubId": CLUB_ID})
    show("create batch", json.dumps(created, ensure_ascii=False)[:220])
    batch_id = (created.get("data") or {}).get("id")
    if not batch_id:
        return 1
    confirmed = http("POST", "/business/edition-final/%s/batch/%s/confirm" % (topic_id, batch_id),
                     token=finance)
    show("finance confirm", json.dumps(confirmed, ensure_ascii=False)[:180])
    paid = http("POST", "/business/edition-final/%s/batch/%s/paid" % (topic_id, batch_id),
                token=operator, json_body={"paymentReference": "E2E-LOCAL-NO-TRANSFER"})
    show("operator paid", json.dumps(paid, ensure_ascii=False)[:220])
    rows = readback("付款批次", "SELECT batch_no,total_amount,batch_status,confirmed_by,paid_by,payment_reference "
                    "FROM club_payable_batch WHERE id=%s", (batch_id,))
    if not rows or rows[0][2] != "PAID" or rows[0][3] == rows[0][4]:
        print("   ✗ 付款批次未闭环或双人分离失效")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(int(sys.argv[1])))
