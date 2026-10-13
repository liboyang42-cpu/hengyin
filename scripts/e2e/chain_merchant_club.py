#!/usr/bin/env python3
"""5-1 商家链路 + 俱乐部链路(开招商→承接→建节点→过审→主题票种模板→锁价)。
用法: python3 scripts/e2e/chain_merchant_club.py <topicId>"""
import sys, pathlib, json, datetime
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from chain_lib import http, q, ex, admin_token, app_token, step, show, readback
from seed_fixtures import RECRUIT_JOB_TARGET

CLUB_PHONE = "13900000003"          # 主题发布者 = 招商方俱乐部主理人
EXEC_CLUB_PHONE = "13900000008"     # 执行俱乐部主理人(必须另一个人,见下面 invite 那步的注释)
EXEC_CLUB_ID = 7002
# ★ 四家不同商家,一家一章 —— 探店日 canonical15 硬性要求 storeCount == 4
#   (TopicSaleCanonicalV1:136-138;storeCount 由 TopicSaleCanonicalBuilder:104 算成去重后的商家数)。
#   一家商家承接四章只算 1 家,开售提审必红 SALE_SNAPSHOT_MISSING_STORE_COUNT。
MERCHANTS = [
    {"phone": "13900000002", "member": 9002, "template": 6001, "perk": 3001, "store": "样板咖啡"},
    {"phone": "13900000005", "member": 9005, "template": 6002, "perk": 3002, "store": "样板书店"},
    {"phone": "13900000006", "member": 9006, "template": 6003, "perk": 3003, "store": "样板花店"},
    {"phone": "13900000007", "member": 9007, "template": 6004, "perk": 3004, "store": "样板面包"},
]


def require_ok(response, label):
    if response.get("code") != 200:
        raise RuntimeError("%s 失败:%s" % (label, response.get("msg") or response))


def resolve_recruit_job(rows):
    if len(rows) != 1:
        raise RuntimeError("%s 注册数必须为 1，实际 %d" % (RECRUIT_JOB_TARGET, len(rows)))
    job_id, job_group, status = rows[0]
    if str(status) != "0":
        raise RuntimeError("%s 未启用，status=%s" % (RECRUIT_JOB_TARGET, status))
    return job_id, job_group


def poster_code_status(response):
    if response.get("code") == 200:
        return "PASS"
    message = response.get("msg") or ""
    if response.get("code") == 500 and "个人码生成服务繁忙" in message:
        return "EXPECTED_BLOCKED_EXTERNAL: 微信小程序码凭据不可用，核心链继续"
    raise RuntimeError("poster-code 未知失败:%s" % (message or response))


def select_topic_ticket_template(rows):
    ids = tuple(row[0] for row in rows)
    return (ids[0], ids[1:]) if ids else (None, ())


def merchant_node_payload(chapter_id, merchant, index):
    return {
        "chapterId": chapter_id,
        "name": "%s第%d站" % (merchant["store"], index + 1),
        "address": "上海市黄浦区%s%d" % (merchant["store"], index + 1),
        "longitude": "%.6f" % (121.48 + index * 0.01),
        "latitude": "%.6f" % (31.24 + index * 0.01),
        "templateId": merchant["template"],
    }


def plan_placeholder_node_bindings(rows, chapters):
    if len(chapters) != len(MERCHANTS) or len(rows) != len(chapters):
        raise RuntimeError("建期占位节点必须与四章一一对应:chapters=%s nodes=%s" %
                           (len(chapters), len(rows)))
    node_by_chapter = {}
    for node_id, chapter_id, merchant_member_id in rows:
        if merchant_member_id is not None or chapter_id not in chapters or chapter_id in node_by_chapter:
            raise RuntimeError("建期占位节点不是每章唯一且未绑定商家:%s" % (rows,))
        node_by_chapter[chapter_id] = node_id
    return tuple((node_by_chapter[chapter_id], merchant["member"])
                 for chapter_id, merchant in zip(chapters, MERCHANTS))


def assert_merchant_node_facts(rows, chapters, require_approved):
    expected = {(chapter_id, merchant["member"]): merchant["template"]
                for chapter_id, merchant in zip(chapters, MERCHANTS)}
    if len(rows) != len(expected):
        raise RuntimeError("商家节点应为 %d 个，实际 %d 个" % (len(expected), len(rows)))
    for row in rows:
        node_id, chapter_id, member_id, audit_status, address, longitude, latitude, template_id = row
        if ((chapter_id, member_id) not in expected
                or template_id != expected.get((chapter_id, member_id))
                or not str(address or "").strip()
                or not str(longitude or "").strip()
                or not str(latitude or "").strip()):
            raise RuntimeError("商家节点 %s 缺 canonical storeCluster 事实:%s" % (node_id, row))
        if require_approved and audit_status != 1:
            raise RuntimeError("商家节点 %s 尚未过审:node_audit_status=%s" %
                               (node_id, audit_status))


def main(tid):
    atok = admin_token(); ctok, _ = app_token(CLUB_PHONE); ptok = ctok
    etok, _ = app_token(EXEC_CLUB_PHONE)
    for m in MERCHANTS:
        m["token"], _ = app_token(m["phone"])
    chapters = [r[0] for r in q("SELECT id FROM cms_topic_chapter WHERE topic_id=%s ORDER BY id", (tid,))]
    show("主题/章节", "topic=%s chapters=%s" % (tid, chapters))

    step("平台后台:逐章打开招商开关(PUT /business/topicchapter,与后台 detail.vue:625 的开关同一条链路)")
    readback("merchant_status/lifecycle", "SELECT merchant_status,lifecycle FROM cms_topic WHERE id=%s", (tid,))
    for cid in chapters:
        # ① 供给条款走它自己的入口(通用 PUT 明确拒收 perkMinValue,并把正确入口写在错误里)
        r1 = http("PUT", "/business/topicchapter/%d/supply-terms" % cid, token=atok, json_body={
            "termsMode": "PERK", "perkMinValue": 30, "maxMerchant": 1,
            "allowedValidationMethods": "QR", "maxNodeXp": 50})
        # ② 招商开关走通用 PUT(与后台 detail.vue:625 的开关同一条)
        r2 = http("PUT", "/business/topicchapter", token=atok,
                  json_body={"id": cid, "topicId": tid, "recruitEnabled": 1, "required": 1,
                             "categoryId": 4001})   # 开招商必须给商家品类(后台 detail.vue:628 同款必填)
        show("chapter %s" % cid, "supply-terms=%s | recruit=%s" % (
            (r1.get("msg") or "")[:40], (r2.get("msg") or "")[:40]))
        try:
            require_ok(r1, "chapter %s supply-terms" % cid)
            require_ok(r2, "chapter %s recruit" % cid)
        except RuntimeError as exc:
            print("   ✗ %s；链路在此中断" % exc)
            return 1
    readback("章节招商开关", "SELECT id,recruit_enabled,terms_mode,perk_min_value,max_merchant,required FROM cms_topic_chapter WHERE topic_id=%s ORDER BY id", (tid,))

    step("回读过审 + 上架状态(商家承接闸要求 status=1 且 userStatus=1)")
    readback("status/userStatus", "SELECT status,user_status,lifecycle FROM cms_topic WHERE id=%s", (tid,))

    step("商家:逐章申请承接(四章四家,一家一章)")
    for cid, m in zip(chapters, MERCHANTS):
        r = http("POST", "/api/merchant/chapter-application/apply", token=m["token"],
                 json_body={"chapterId": cid, "message": "e2e 申请承接"})
        show("apply chapter %s (%s)" % (cid, m["store"]), (r.get("msg") or "")[:44])
    readback("申请行", "SELECT id,chapter_id,merchant_id,status FROM merchant_chapter_application WHERE topic_id=%s ORDER BY id", (tid,))

    step("俱乐部:逐条通过承接申请")
    for (aid,) in q("SELECT id FROM merchant_chapter_application WHERE topic_id=%s AND status=0 ORDER BY id", (tid,)):
        r = http("POST", "/api/merchant/chapter-application/audit", token=ctok,
                 json_body={"applicationId": aid, "approved": True, "status": 1})
        show("audit application %s" % aid, (r.get("msg") or "")[:40])
    readback("申请状态", "SELECT id,chapter_id,status FROM merchant_chapter_application WHERE topic_id=%s ORDER BY id", (tid,))
    # merchant_chapter_offer 在 recruitEnabled=1 的新模型下本就不落行(MerchantOfferServiceImpl:361),不再找它

    step("商家:逐章入驻供给(POST /api/coop/offer/enroll → 落 merchant_chapter_offer)")
    # ★上一轮我判定「merchant_chapter_offer 在新模型下本就不落行」是【错的】:它照样要落,
    #   只是由商家自己的「填写实际供给」这一步落(MerchantOfferServiceImpl.enroll:84),
    #   而不是审批通过时自动建。没有它:poster-code 红在「承接已失效或未生效」,
    #   且购票预占容量 = 0(IMerchantOfferService.java:9-14 的注释明写)。
    for cid, m in zip(chapters, MERCHANTS):
        r = http("POST", "/api/coop/offer/enroll", token=m["token"], json_body={
            "chapterId": cid, "termsMode": "PERK", "perkTemplateId": m["perk"],
            "quotaTotal": 20, "allowedValidationMethods": "QR", "maxNodeXp": 50})
        show("offer enroll ch%s (%s)" % (cid, m["store"]), (r.get("msg") or "")[:46])
    readback("merchant_chapter_offer", "SELECT id,topic_id,chapter_id,merchant_id,quota_total,quota_used,status,terms_mode FROM merchant_chapter_offer WHERE topic_id=%s ORDER BY id", (tid,))

    step("平台:把建期每章唯一占位节点绑定给承接商家")
    # 建期 V2 blocks 已引用占位 nodeId。复用同一行既保住剧情引用，又避免商家 submit
    # 另插一行后玩家看到 4 个占位点 + 4 个商家点。绑定后 submit 会按 chapter+merchant
    # 找到原行并更新为真实门店内容，仍完整经过商家提交与俱乐部审核。
    placeholder_rows = q(
        "SELECT id,chapter_id,merchant_member_id FROM cms_topic_node WHERE topic_id=%s ORDER BY id",
        (tid,))
    try:
        bindings = plan_placeholder_node_bindings(placeholder_rows, chapters)
        for node_id, merchant_member_id in bindings:
            require_ok(http("PUT", "/business/topicnode", token=atok,
                            json_body={"id": node_id, "merchantMemberId": merchant_member_id}),
                       "bind placeholder node %s" % node_id)
    except RuntimeError as exc:
        print("   ✗ %s；链路在此中断" % exc)
        return 1

    step("商家:逐章提交自己的真实节点内容(带各自玩法模板与门店坐标)")
    for i, (cid, m) in enumerate(zip(chapters, MERCHANTS)):
        r = http("POST", "/api/merchant/chapter-node/submit", token=m["token"],
                 json_body=merchant_node_payload(cid, m, i))
        show("node submit ch%s (%s)" % (cid, m["store"]), (r.get("msg") or "")[:44])
        try:
            require_ok(r, "node submit chapter %s" % cid)
        except RuntimeError as exc:
            print("   ✗ %s；链路在此中断" % exc)
            return 1
    node_sql = ("SELECT id,chapter_id,merchant_member_id,node_audit_status,address,longitude,latitude,"
                "template_id FROM cms_topic_node WHERE topic_id=%s ORDER BY id")
    nodes = readback("主题全部节点 canonical 事实", node_sql, (tid,))
    try:
        assert_merchant_node_facts(nodes, chapters, require_approved=False)
    except RuntimeError as exc:
        print("   ✗ %s；链路在此中断" % exc)
        return 1

    step("俱乐部:逐个节点过审")
    for (nid,) in q("SELECT id FROM cms_topic_node WHERE topic_id=%s AND merchant_member_id IS NOT NULL ORDER BY id", (tid,)):
        # 该端点收 form 参数 (Long nodeId, Boolean approve, String reason),不是 JSON
        r = http("POST", "/api/merchant/chapter-node/audit", token=ctok,
                 form={"nodeId": nid, "approve": "true", "reason": "e2e 准入"})
        show("node audit %s" % nid, (r.get("msg") or "")[:40])
        try:
            require_ok(r, "node audit %s" % nid)
        except RuntimeError as exc:
            print("   ✗ %s；链路在此中断" % exc)
            return 1
    nodes = readback("主题全部节点过审 canonical 事实", node_sql, (tid,))
    try:
        assert_merchant_node_facts(nodes, chapters, require_approved=True)
    except RuntimeError as exc:
        print("   ✗ %s；链路在此中断" % exc)
        return 1

    step("发布者 → 执行俱乐部发 type1 合作邀约,俱乐部接受 ⇒ 落 ORGANIZER coop_order")
    # ★必须在【招募期内且锁价前】做,顺序不能挪:
    #   · RecruitmentWindowPolicy.assertOpen 要求 now < recruitDeadline(过期即「招商写入已关闭」);
    #   · acceptInviteWithLock 在 lifecycle>=PRICING 时拒「主题已锁价,不能再接受合作邀请」(冻结阵容)。
    # ★执行俱乐部必须是【另一个人】的:/api/coop/invite 禁自邀自接(ApiCoopController:214-217),
    #   而发起人又必须是主题发布者(:226-233);发布者本人还得是 club_leader 才能开商家池。
    # 开售闸 resolveExecutingClub:198-209 硬性要求阵容里有一条 CLUB/ORGANIZER 的 coop_order,
    # 没有它 → SALE_EXECUTING_CLUB_UNRESOLVED。冻结列(role/scope/选集/证据规则)由
    # CoopInviteServiceImpl:1309-1330 依 toType=club + nodeId=null 自动落,不用也不该手填。
    r = http("POST", "/api/coop/invite", token=ptok, json_body={
        "topicId": tid, "inviteType": 1, "toType": "club", "toId": EXEC_CLUB_ID,
        "shareMode": 0, "message": "e2e 请俱乐部承接执行"})
    show("coop/invite", json.dumps(r, ensure_ascii=False)[:180])
    if r.get("code") != 200:
        print("   ✗ 俱乐部合作邀约失败,链路在此中断"); return 1
    for (iid,) in q("SELECT id FROM coop_invite WHERE topic_id=%s AND status=0 ORDER BY id", (tid,)):
        r = http("POST", "/api/coop/handle", token=etok, json_body={"id": iid, "status": 1})
        show("coop/handle accept %s" % iid, json.dumps(r, ensure_ascii=False)[:180])
    orders = readback("coop_order 冻结列", "SELECT id,role_snapshot,scope_type_snapshot,scope_id_snapshot,"
             "eligible_purchase_kinds_snapshot,fulfillment_evidence_rule_snapshot,status,del_flag "
             "FROM coop_order WHERE topic_id=%s", (tid,))
    if len(orders) != 1 or orders[0][1] != "ORGANIZER":
        print("   ✗ ORGANIZER 合作单未形成,链路在此中断"); return 1

    step("商家:取海报码")
    for (nid,) in q("SELECT id FROM cms_topic_node WHERE topic_id=%s AND merchant_member_id IS NOT NULL ORDER BY id LIMIT 1", (tid,)):
        r = http("POST", "/api/merchant/chapter-node/poster-code", token=MERCHANTS[0]["token"], form={"nodeId": nid})
        show("poster-code node %s" % nid, json.dumps(r, ensure_ascii=False)[:240])
        try:
            show("poster-code 状态", poster_code_status(r))
        except RuntimeError as exc:
            print("   ✗ %s；链路在此中断" % exc)
            return 1

    step("主题层:准备唯一 guided 票种模板(实际 4 场由 open-session 生成)")
    # ★ 顺序不能挪,也不能靠 pricing/confirm 代建:
    #    · TopicLifecycleState.isCommercialTermsFrozen:37-39 从 PRICING 起就冻售卖目录
    #      (OmsTicketServiceImpl:255-258 当场 409,文案还错写成「已进入 SELLING」);
    #    · 而 PricingServiceImpl.confirmPrice:350-390 若找不到 sub_type=guided 的票,会在
    #      【正要进 PRICING 的同一刻】新建一张,且全程不设 startTime/endTime/signupDeadline
    #      ⇒ 那张票的时间此后永远填不上,canonical15 的 validateSessionWindows:155-160 必红。
    #    主题层只需要一张带时间的 guided 票种模板；每次 open-session 会把这一张复制到
    #    对应 cms_activity。把四场写成四张主题票会让每个活动都复制四张，最终形成 4x4 重复。
    base = q("SELECT start_date FROM cms_topic WHERE id=%s", (tid,))[0][0].replace(
        hour=10, minute=0, second=0, microsecond=0)
    existing = q("SELECT id FROM oms_ticket WHERE owner_type=1 AND owner_id=%s "
                 "AND sub_type='guided' AND del_flag=0 ORDER BY id", (tid,))
    template_id, duplicate_ids = select_topic_ticket_template(existing)
    ticket = {
        "ownerType": 1, "ownerId": tid, "name": "标准场次票", "subType": "guided", "mode": 2,
        "price": 69, "totalInventory": 20, "delFlag": 0,
        "startTime": base.strftime("%Y-%m-%d %H:%M:%S"),
        "endTime": (base + datetime.timedelta(hours=4)).strftime("%Y-%m-%d %H:%M:%S"),
        "signupDeadline": (base - datetime.timedelta(hours=2)).strftime("%Y-%m-%d %H:%M:%S"),
        "meetingPoint": "人民广场1号口", "gatherLng": "121.473701", "gatherLat": "31.230416",
        "refundRule": "开场前24小时可退", "memberId": 9003,
    }
    if template_id is not None:
        ticket["id"] = template_id
    method = "PUT" if template_id is not None else "POST"
    result = http(method, "/business/ticket", token=atok, json_body=ticket)
    try:
        require_ok(result, "更新主题票种模板" if template_id is not None else "创建主题票种模板")
        for duplicate_id in duplicate_ids:
            require_ok(http("PUT", "/business/ticket", token=atok, json_body={
                "id": duplicate_id, "ownerType": 1, "ownerId": tid, "delFlag": 2,
            }), "下架重复主题票 %s" % duplicate_id)
    except RuntimeError as exc:
        print("   ✗ %s；链路在此中断" % exc)
        return 1
    topic_tickets = readback(
        "唯一主题票种模板",
        "SELECT id,name,sub_type,price,total_inventory,start_time,end_time,signup_deadline "
        "FROM oms_ticket WHERE owner_type=1 AND owner_id=%s AND del_flag=0 ORDER BY id", (tid,))
    if len(topic_tickets) != 1 or topic_tickets[0][2] != "guided":
        print("   ✗ 主题层必须且只能保留一张 guided 票种模板")
        return 1

    step("招募到期任务:RECRUITING → PRICING(topicRecruitDeadlineTask)")
    # 只把等待 5 天压缩为立即执行；recruitDeadline 已由建期 API 正常落库。
    ex("UPDATE cms_topic SET recruit_deadline = DATE_SUB(NOW(), INTERVAL 1 MINUTE) WHERE id=%s", (tid,))
    try:
        job_id, job_group = resolve_recruit_job(q(
            "SELECT job_id,job_group,status FROM sys_job WHERE invoke_target=%s ORDER BY job_id",
            (RECRUIT_JOB_TARGET,)))
    except RuntimeError as exc:
        print("   ✗ %s；链路在此中断" % exc)
        return 1
    job_result = http("PUT", "/monitor/job/run", token=atok,
                      json_body={"jobId": job_id, "jobGroup": job_group})
    show("run job %s" % job_id, json.dumps(job_result, ensure_ascii=False)[:180])
    try:
        require_ok(job_result, RECRUIT_JOB_TARGET)
    except RuntimeError as exc:
        print("   ✗ %s；链路在此中断" % exc)
        return 1
    import time; time.sleep(5)
    lifecycle = readback("lifecycle(应为 2=PRICING)", "SELECT lifecycle FROM cms_topic WHERE id=%s", (tid,))
    if not lifecycle or lifecycle[0][0] != 2:
        print("   ✗ 招募到期未进入 PRICING,链路在此中断"); return 1

    step("俱乐部:锁价(真实 4 场由后续 open-session 生成)")
    r = http("POST", "/api/topic/pricing/confirm", token=ctok,
             json_body={"topicId": tid, "guidedPrice": 69, "guidedInventory": 20,
                        "leadCost": 0, "teamSize": 6})
    show("pricing/confirm", json.dumps(r, ensure_ascii=False)[:220])
    if r.get("code") != 200:
        print("   ✗ 锁价失败,链路在此中断"); return 1
    readback("lifecycle", "SELECT lifecycle FROM cms_topic WHERE id=%s", (tid,))
    readback("主题票种模板(仍应只有一张)", "SELECT id,name,price,start_time,end_time FROM oms_ticket "
             "WHERE owner_type=1 AND owner_id=%s AND del_flag=0 ORDER BY id", (tid,))

    return 0


if __name__ == "__main__":
    sys.exit(main(int(sys.argv[1])))
