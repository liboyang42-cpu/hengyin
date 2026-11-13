#!/usr/bin/env python3
"""5-1 资金段：质量确认 → FINAL 六量 → 应付批次 → 双人确认/付款登记。

用法: python3 scripts/e2e/chain_finance.py <topicId>
前置: chain_player.py 已完成一笔本地桩支付和四章核销。
"""
import decimal, hashlib, json, pathlib, sys
from zoneinfo import ZoneInfo

sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from chain_lib import admin_token, db, http, q, readback, show, step

CLUB_ID = 7001  # 与 chain_sale_close.EXEC_CLUB_ID 同一个执行俱乐部(内容所属俱乐部,P-01 起为 7001)
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


def terms_config_hash(row, evidence_due, fulfillment_end):
    """与 ClubEditionCompensationServiceImpl.configHash 同口径的 SHA-256 指纹。
    row = (topic_id, executing_club_id, fixed_compensation, extra_hour_rate,
           quality_dimension_amount, quality_dimensions, extra_approver_user_id, headcount_rate, headcount_cap)"""
    shanghai = ZoneInfo("Asia/Shanghai")
    millis = lambda value: str(int(value.replace(tzinfo=shanghai).timestamp() * 1000))
    parts = [str(row[0]), str(row[1]), plain(row[2]), plain(row[3]), plain(row[4]), str(row[5]),
             millis(evidence_due), millis(fulfillment_end), str(row[6])]
    if (row[7] is not None and row[7] != decimal.Decimal("0")) or row[8] is not None:
        parts = ["\x1f".join(parts), plain(row[7]), plain(row[8])]
    return hashlib.sha256("\x1f".join(parts).encode()).hexdigest()


def seed_legacy_sale_freeze(topic_id):
    """开售冻结夹具(已售合同形态)。

    四店固定补偿产品已停止新开售(TopicSaleFinalizationServiceImpl:不再调用
    freezeTermsForSale / snapshotForSale),本链路的自由探索主题开售后不再有条款与收款人快照,
    但"已售合同继续履行"的 质量确认 → FINAL → 应付批次 仍是线上存量主题的真实资金路径。
    这里以与 freezeTermsForSale / snapshotForSale 同口径的值补上开售当时本该冻结的两行
    (条款 + 收款人快照 + 三维 PENDING 种子),数值全部取自本链路前序步骤已通过真实入口写入的
    成本版本与已审核收款人;产品代码对它们的全部校验(指纹、执行俱乐部、收款人快照)照常生效。
    已有条款的主题不重复写。"""
    conn = db()
    conn.autocommit(False)
    cur = conn.cursor()
    try:
        cur.execute("SELECT 1 FROM club_edition_compensation_terms WHERE topic_id=%s FOR UPDATE", (topic_id,))
        if cur.fetchone():
            conn.commit()
            return
        cur.execute("""SELECT fixed_compensation,extra_approver_user_id,headcount_rate,headcount_cap
                         FROM topic_edition_cost WHERE topic_id=%s AND is_current=1""", (topic_id,))
        cost = cur.fetchone()
        if not cost or cost[1] is None or cost[1] <= 0:
            raise RuntimeError("缺少当期成本版本或项目负责人,无法补开售冻结夹具")
        cur.execute("""SELECT id,profile_version,facts_hash,license_hash FROM club_corporate_payee_profile
                        WHERE club_id=%s AND is_current=1 AND verify_status='VERIFIED'""", (CLUB_ID,))
        payee = cur.fetchone()
        if not payee:
            raise RuntimeError("缺少已审核的当前对公收款人,无法补开售冻结夹具")
        cur.execute("SELECT selling_started_at,DATE_ADD(NOW(),INTERVAL 1 DAY),DATE_ADD(NOW(),INTERVAL 8 DAY) "
                    "FROM cms_topic WHERE id=%s", (topic_id,))
        frozen_at, fulfillment_end, evidence_due = cur.fetchone()
        base = (topic_id, CLUB_ID, cost[0], decimal.Decimal("40"), decimal.Decimal("200"),
                ",".join(QUALITY_DIMENSIONS), cost[1], cost[2], cost[3])
        cur.execute("""INSERT INTO club_edition_compensation_terms
                         (topic_id,executing_club_id,fixed_compensation,extra_hour_rate,quality_dimension_amount,
                          quality_dimensions,evidence_due_at,fulfillment_end_at,extra_approver_user_id,
                          headcount_rate,headcount_cap,config_hash,frozen_at)
                       VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s)""",
                    (topic_id, CLUB_ID, cost[0], base[3], base[4], base[5], evidence_due, fulfillment_end,
                     cost[1], cost[2], cost[3], terms_config_hash(base, evidence_due, fulfillment_end),
                     frozen_at))
        for dimension in QUALITY_DIMENSIONS:
            cur.execute("""INSERT INTO club_quality_decision
                             (topic_id,club_id,dimension,decision_status,excluded_by_fault,due_at,decision_version,is_current)
                           VALUES (%s,%s,%s,'PENDING',0,%s,1,1)""",
                        (topic_id, CLUB_ID, dimension, evidence_due))
        cur.execute("""INSERT INTO topic_sale_payee_snapshot
                         (topic_id,club_id,profile_id,profile_version,facts_hash,license_hash,frozen_at)
                       SELECT %s,%s,%s,%s,%s,%s,%s FROM dual
                        WHERE NOT EXISTS (SELECT 1 FROM topic_sale_payee_snapshot WHERE topic_id=%s AND club_id=%s)""",
                    (topic_id, CLUB_ID, payee[0], payee[1], payee[2], payee[3], frozen_at, topic_id, CLUB_ID))
        conn.commit()
    except Exception:
        conn.rollback()
        raise
    finally:
        cur.close(); conn.close()


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
        config_hash = terms_config_hash(row, evidence_due, fulfillment_end)
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

    step("开售冻结夹具:四店固定补偿已停售,补上已售合同形态的条款/收款人快照(见 seed_legacy_sale_freeze)")
    seed_legacy_sale_freeze(topic_id)
    readback("冻结条款/收款人快照", "SELECT (SELECT COUNT(*) FROM club_edition_compensation_terms WHERE topic_id=%s),"
             "(SELECT COUNT(*) FROM topic_sale_payee_snapshot WHERE topic_id=%s),"
             "(SELECT COUNT(*) FROM club_quality_decision WHERE topic_id=%s AND decision_status='PENDING')",
             (topic_id, topic_id, topic_id))

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
