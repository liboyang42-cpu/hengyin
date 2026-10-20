#!/usr/bin/env python3
"""5-1 收口段:PRICING → 开售审 → SELLING,并回读开售快照 15 项非空。

用法: python3 scripts/e2e/chain_sale_close.py <topicId>
前置: chain_platform.py + chain_merchant_club.py 已把主题带到 PRICING(见那两个脚本)。

★ 这一段的每一步都是【真实入口】,没有一处 SQL 直插产品数据。顺序是被闸逼出来的,不是我编的:
  finalizeForSale 依次要 活动票投影 → 对公收款人 → 四类计划工时 → 当期成本版本,
  少哪一样它就报哪一个错误码,逐个撞出来的。
"""
import sys, pathlib, json, datetime
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from chain_lib import http, q, admin_token, app_token, step, show, readback

EXEC_CLUB_ID = 7002
EXEC_CLUB_PHONE = "13900000008"
PAYEE_LEGAL_NAME = "E2E 测试俱乐部有限公司"
PAYEE_LICENSE_OBJECT_KEY = "e2e/fake-license.png"
PAYEE_LICENSE_HASH = "e2e-fake-license-hash-0000000000"
CANONICAL_FIELDS = ["storeCount", "ticketPricingPolicy", "salesTargetTotal", "sessionWindows",
                    "participationUnitPolicy", "sessionVenues", "merchantCashSettlementMode", "storeCluster",
                    "microExperienceCatalog", "merchantWorkPackage", "clubWorkPackage", "contentRightsPackage",
                    "completionPolicy", "relationshipPolicy", "refundReschedulePolicy"]
SESSION_COUNTS_SQL = (
    "SELECT DATE_FORMAT(start_date,'%%Y-%%m-%%d'),COUNT(*) FROM cms_activity "
    "WHERE topic_id=%s AND club_id=%s AND del_flag=0 "
    "GROUP BY DATE_FORMAT(start_date,'%%Y-%%m-%%d') ORDER BY DATE_FORMAT(start_date,'%%Y-%%m-%%d')"
)


def session_days_from_template(rows):
    if len(rows) != 1 or rows[0][0] is None:
        raise RuntimeError("主题层必须且只能有一张带 start_time 的 guided 票种模板")
    first = rows[0][0].date()
    return tuple((first + datetime.timedelta(days=i)).isoformat() for i in range(4))


def pending_session_days(planned_days, existing_rows):
    counts = {str(day): int(count) for day, count in existing_rows}
    unexpected = set(counts) - set(planned_days)
    if unexpected:
        raise RuntimeError("存在计划外场次:%s" % sorted(unexpected))
    duplicated = sorted(day for day, count in counts.items() if count != 1)
    if duplicated:
        raise RuntimeError("重复场次:%s" % duplicated)
    return tuple(day for day in planned_days if day not in counts)


def recover_key_bound_payee_fixture(view_response, profile_rows, current_topic_snapshots,
                                    delete_fixture):
    """本地 PII key 轮换后，只清理由本 E2E 明确标记的密钥绑定夹具。"""
    if view_response.get("code") == 200:
        return "READABLE"
    if not profile_rows:
        return "ABSENT"
    if view_response.get("code") != 500:
        raise RuntimeError("对公收款人预检异常:%s" % view_response)
    if current_topic_snapshots:
        raise RuntimeError("当前主题已有对公收款人快照，拒绝重置 PII fixture")
    if any(row[1:] != (PAYEE_LICENSE_OBJECT_KEY, PAYEE_LICENSE_HASH) for row in profile_rows):
        raise RuntimeError("存在非 E2E 对公收款人资料，拒绝重置:%s" % (profile_rows,))
    deleted = delete_fixture()
    if deleted != len(profile_rows):
        raise RuntimeError("E2E PII fixture 重置行数异常:期望 %d，实际 %d" %
                           (len(profile_rows), deleted))
    return "RESET_KEY_BOUND_FIXTURE"


def assert_payee_readback(response, expected_status):
    data = response.get("data") if isinstance(response, dict) else None
    if (response.get("code") != 200 or not isinstance(data, dict)
            or data.get("legalName") != PAYEE_LEGAL_NAME
            or data.get("verifyStatus") != expected_status):
        raise RuntimeError("对公收款人 PII 当前密钥回读失败:%s" % response)
    return data


def main(tid):
    atok = admin_token()
    etok, _ = app_token(EXEC_CLUB_PHONE)

    step("执行俱乐部主理人:通过真实入口登记发布者实名(开场次同属发布行为)")
    status = http("POST", "/api/publisher/identity/status", token=etok, json_body={})
    if not ((status.get("data") or {}).get("registered")):
        status = http("POST", "/api/publisher/identity", token=etok, json_body={
            "realName": "测试执行主理人", "idCard": "110101199001010031",
            "consent": True, "source": "topic_publish"})
    show("publisher/identity", json.dumps(status, ensure_ascii=False)[:180])

    step("俱乐部开团:一天一场,开 4 场(POST /api/club/open-session)")
    # ★ 这就是任务书里「俱乐部接手主题 → 定 4 个场次」的真实入口。
    #   ClubSessionServiceImpl.open 要求该俱乐部对本主题有【生效合作单】,然后建 cms_activity
    #   并把主题票复制成活动票 —— 而 finalizeForSale 的 assertActivityTicketProjection
    #   要的正是「每个活动的票与主题票 1:1 对得上」,没有它恒红 SALE_TICKET_PROJECTION_MISSING。
    try:
        session_days = session_days_from_template(q(
            "SELECT start_time FROM oms_ticket WHERE owner_type=1 AND owner_id=%s "
            "AND sub_type='guided' AND del_flag=0 ORDER BY id", (tid,)))
        existing_rows = q(SESSION_COUNTS_SQL, (tid, EXEC_CLUB_ID))
        pending = set(pending_session_days(session_days, existing_rows))
    except RuntimeError as exc:
        print("   ✗ %s；链路在此中断" % exc)
        return 1
    for day in session_days:
        if day not in pending:
            show("open-session %s" % day, "ALREADY_EXISTS: 幂等跳过")
            continue
        r = http("POST", "/api/club/open-session", token=etok,
                 json_body={"clubId": EXEC_CLUB_ID, "topicId": tid,
                            "sessionDate": day, "sessionTime": "10:00"})
        show("open-session %s" % day, json.dumps(r, ensure_ascii=False)[:120])
        if r.get("code") != 200:
            print("   ✗ open-session %s 失败，链路在此中断" % day)
            return 1
    readback("cms_activity", "SELECT id,topic_id,club_id,start_date,end_date,status,publish_status "
             "FROM cms_activity WHERE topic_id=%s ORDER BY id", (tid,))
    session_counts = readback(
        "四个不同日期各一场",
        SESSION_COUNTS_SQL,
        (tid, EXEC_CLUB_ID))
    try:
        if pending_session_days(session_days, session_counts):
            raise RuntimeError("四个计划日期未全部开场")
    except RuntimeError as exc:
        print("   ✗ %s；链路在此中断" % exc)
        return 1
    readback("活动票投影(每场应与主题票同数)",
             "SELECT owner_id,COUNT(*) FROM oms_ticket WHERE owner_type=2 AND owner_id IN "
             "(SELECT id FROM cms_activity WHERE topic_id=%s) GROUP BY owner_id", (tid,))

    step("俱乐部对公收款人:建资料 → 审核通过(SALE_PAYEE_NOT_VERIFIED 就是缺它)")
    # 明显假值,不编得像真的;真实入口在后台 /business/club-payee。
    profile = {"legalName": PAYEE_LEGAL_NAME, "unifiedSocialCreditCode": "E2ETESTE2ETESTE2E0",
               "licenseObjectKey": PAYEE_LICENSE_OBJECT_KEY, "licenseHash": PAYEE_LICENSE_HASH,
               "bankAccountName": PAYEE_LEGAL_NAME, "bankName": "E2E 测试银行营业部",
               "corporateBankAccountNo": "0000000000000000001", "invoiceType": "NONE",
               "taxNo": "E2ETESTE2ETESTE2E0", "financeContactName": "E2E 财务",
               "financeContactPhone": EXEC_CLUB_PHONE}
    view = http("GET", "/business/club-payee/%d" % EXEC_CLUB_ID, token=atok)
    raw_profiles = q("SELECT id,license_object_key,license_hash FROM club_corporate_payee_profile "
                     "WHERE club_id=%s ORDER BY id", (EXEC_CLUB_ID,))
    current_snapshots = q("SELECT id FROM topic_sale_payee_snapshot WHERE topic_id=%s AND club_id=%s",
                          (tid, EXEC_CLUB_ID))
    try:
        fixture_state = recover_key_bound_payee_fixture(
            view, raw_profiles, current_snapshots,
            lambda: ex("DELETE FROM club_corporate_payee_profile WHERE club_id=%s "
                       "AND license_object_key=%s AND license_hash=%s",
                       (EXEC_CLUB_ID, PAYEE_LICENSE_OBJECT_KEY, PAYEE_LICENSE_HASH)))
        show("PII fixture 预检", fixture_state)
    except RuntimeError as exc:
        print("   ✗ %s；链路在此中断" % exc)
        return 1
    saved = http("PUT", "/business/club-payee/%d" % EXEC_CLUB_ID, token=atok,
                 json_body=profile)
    show("saveProfile", json.dumps(saved, ensure_ascii=False)[:180])
    try:
        assert_payee_readback(saved, "PENDING")
    except RuntimeError as exc:
        print("   ✗ %s；链路在此中断" % exc)
        return 1
    rows = q("SELECT id FROM club_corporate_payee_profile WHERE club_id=%s AND is_current=1", (EXEC_CLUB_ID,))
    if len(rows) != 1:
        print("   ✗ 对公收款人 current 版本不是唯一一条；链路在此中断")
        return 1
    verified = http("POST", "/business/club-payee/%d/verify/%d" % (EXEC_CLUB_ID, rows[0][0]), token=atok,
                    json_body={"pass": True, "licenseHash": profile["licenseHash"]})
    show("verify", json.dumps(verified, ensure_ascii=False)[:180])
    try:
        assert_payee_readback(verified, "VERIFIED")
    except RuntimeError as exc:
        print("   ✗ %s；链路在此中断" % exc)
        return 1
    readback("对公收款人", "SELECT id,club_id,profile_version,is_current,verify_status FROM club_corporate_payee_profile "
             "WHERE club_id=%s", (EXEC_CLUB_ID,))

    step("平台后台:逐章填写商家工作包六项(真实章节写入口)")
    work_package = {"roleNeed": "店员1名", "effortStaffMinutes": 30, "effortVenue": "柜台",
                    "effortMaterials": "礼包", "effortAvailableSlots": "10:00-18:00",
                    "effortQualifications": "在岗"}
    for chapter_id, in q("SELECT id FROM cms_topic_chapter WHERE topic_id=%s ORDER BY id", (tid,)):
        result = http("PUT", "/business/topicchapter/%s/work-package" % chapter_id,
                      token=atok, json_body=work_package)
        show("work-package chapter %s" % chapter_id, json.dumps(result, ensure_ascii=False)[:120])
        if result.get("code") != 200:
            return 1
    readback("商家工作包(API)", "SELECT chapter_id,role_need,effort_staff_minutes,effort_venue "
             "FROM cms_topic_chapter_template_meta WHERE topic_id=%s ORDER BY chapter_id", (tid,))

    step("四类计划工时 H_plan(缺一类即 EDITION_HOURS_PLAN_INCOMPLETE 并点名)")
    for kind, hours in (("PREP", 8), ("CONTENT", 6), ("ONSITE", 12), ("REVIEW", 4)):
        show(kind, (http("PUT", "/business/club-compensation/%d/hours/plan" % tid, token=atok,
                         json_body={"clubId": EXEC_CLUB_ID, "hourKind": kind,
                                    "plannedHours": hours}).get("msg") or "")[:30])
    readback("club_edition_hours", "SELECT hour_kind,planned_hours,confirm_status FROM club_edition_hours "
             "WHERE topic_id=%s ORDER BY hour_kind", (tid,))

    step("当期成本版本 topic_edition_cost(固定补偿必须 ≥ 合同下界,低了会点名下界数字)")
    cost = {"taxCost": 0, "paymentCost": 0, "materialCost": 0, "kitCost": 0, "insuranceRiskCost": 0,
            "acquisitionCost": 0, "platformLaborCost": 0, "otherCost": 0,
            "fixedCompensation": 2000, "bonuses": 0,
            "platformOnsiteHours": 0, "breakEvenFixedCost": 0, "variableCostPerTicket": 0,
            "extraApproverUserId": 1, "note": "E2E 端到端最小成本版本"}
    show("saveCost", (http("PUT", "/business/topic-edition-funds/%d/cost" % tid, token=atok,
                           json_body=cost).get("msg") or "")[:40])
    readback("topic_edition_cost", "SELECT cost_version,is_current,fixed_compensation,extra_approver_user_id "
             "FROM topic_edition_cost WHERE topic_id=%s ORDER BY cost_version", (tid,))

    step("声明 ORGANIZER actor(SALE_SNAPSHOT_MISSING_ORGANIZER_ACTOR 就是缺它)")
    show("organizer-actor", (http("PUT", "/business/topic/%d/organizer-actor" % tid,
                                 token=atok).get("msg") or "")[:30])

    step("canonical15 人工事实草稿(PUT /business/audit/topic/{id}/sale-canonical-draft)")
    # 机器可推的段(票/章/offer/门店/权益/组织方)由 TopicSaleCanonicalBuilder.rebuild 从库里权威读回覆盖,
    # 草稿只负责人工事实。形状照仓库自带正控 TopicSaleCanonicalTestFixture。
    import subprocess
    draft = subprocess.run([sys.executable, str(pathlib.Path(__file__).resolve().parent / "canonical_draft.py"),
                            str(tid)], capture_output=True, text=True)
    print(draft.stdout.rstrip() or draft.stderr[-500:])

    step("开售提审 → 审核通过 → SELLING")
    show("sale-submit", json.dumps(http("POST", "/business/audit/topic/%d/sale-submit" % tid, token=atok),
                                   ensure_ascii=False)[:200])
    for (task_id,) in q("SELECT id FROM audit_task WHERE biz_id=%s AND biz_type='TOPIC_SALE_REVIEW' "
                        "AND status=0 ORDER BY id", (tid,)):
        show("review %s" % task_id, json.dumps(http("POST", "/business/audit/review", token=atok,
             json_body={"taskId": task_id, "status": 1, "reasonCode": "E2E", "reasonText": "端到端放行"}),
             ensure_ascii=False)[:200])
    readback("lifecycle/selling_started_at", "SELECT lifecycle,selling_started_at FROM cms_topic WHERE id=%s", (tid,))

    step("★开售快照 15 项非空回读(真 SQL 直读 canonical_json,不问接口)")
    rows = q("SELECT canonical_json FROM topic_sale_snapshot WHERE topic_id=%s", (tid,))
    if not rows:
        print("   ✗ 没有开售快照,链路在此中断"); return 1
    canonical = json.loads(rows[0][0])
    ok = True
    for i, field in enumerate(CANONICAL_FIELDS, 1):
        value = canonical.get(field)
        empty = value is None or (isinstance(value, (list, dict, str)) and len(value) == 0)
        ok = ok and not empty
        print("  %2d. %-28s %s  %s" % (i, field, "✗空" if empty else "✓非空",
                                       json.dumps(value, ensure_ascii=False)[:80]))
    print("\n  15/15 非空: %s | 顶层字段数: %d | 字段顺序与持久化合同一致: %s"
          % (ok, len(canonical), list(canonical.keys()) == CANONICAL_FIELDS))
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main(int(sys.argv[1])))
