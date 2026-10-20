#!/usr/bin/env python3
"""5-1 平台链路:建期 → 配 4 章节 → 立项审 → 通过 → 交给商家/俱乐部链推进至 PRICING。"""
import sys, pathlib, datetime, json
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from chain_lib import http, q, admin_token, app_token, step, show, readback

PUBLISHER_PHONE = "13900000003"   # 俱乐部主理人本人建期:开招商与转交俱乐部都要求这个身份
PUBLISHER_MEMBER = 9003
CLUB_ID = 7001
BASE_DATE = datetime.datetime.now().replace(hour=9, minute=0, second=0, microsecond=0)


def d(off):
    """带 @JsonFormat 的字段用这个(yyyy-MM-dd HH:mm:ss)。"""
    return (BASE_DATE + datetime.timedelta(days=off)).strftime("%Y-%m-%d %H:%M:%S")


def assert_expected_sale_block(response, state_rows, snapshot_rows):
    message = response.get("msg") or ""
    if response.get("code") != 500 or "PRICING" not in message:
        raise RuntimeError("sale-submit 未按 PRICING 状态闸阻断:%s" % message)
    if state_rows != ((1, None),):
        raise RuntimeError("预期阻断后 lifecycle/selling_started_at 异常:%s" % (state_rows,))
    if snapshot_rows != ((0,),):
        raise RuntimeError("预期阻断后不应产生开售快照:%s" % (snapshot_rows,))
    return "EXPECTED_BLOCKED: lifecycle=1，交由后续链推进 PRICING 后再提审"


def club_publish_fields():
    """与小程序俱乐部发布页一致，建期时就声明主题归属。"""
    return {"clubId": CLUB_ID}


def fixture_topic_image_fields():
    """空值明确触发现有封面 fallback，避免裸文件名被当网络图片请求。"""
    return {"imgUrl": ""}


def assert_topic_club_ownership(rows):
    expected = ((PUBLISHER_MEMBER, CLUB_ID),)
    if rows != expected:
        raise RuntimeError("主题发布归属异常:期望 member_id/club_id=%s，实际=%s" %
                           (expected[0], rows))


def main():
    atok = admin_token()
    ptok, _ = app_token(PUBLISHER_PHONE)
    assert atok and ptok

    # 本地链路可重复执行：只下架同一测试发布者此前创建的 E2E 主题，释放在架配额。
    for old_id, in q("SELECT id FROM cms_topic WHERE member_id=%s AND name='E2E 探店日一期' "
                     "AND user_status=1 ORDER BY id", (PUBLISHER_MEMBER,)):
        result = http("POST", "/api/topic/update_user_status?id=%s&expectedUserStatus=1" % old_id,
                      token=ptok)
        if result.get("code") != 200:
            show("下架旧 E2E 主题 %s" % old_id, json.dumps(result, ensure_ascii=False)[:180])
            return 1

    step("俱乐部主理人:通过真实入口登记发布者实名")
    status = http("POST", "/api/publisher/identity/status", token=ptok, json_body={})
    if not ((status.get("data") or {}).get("registered")):
        status = http("POST", "/api/publisher/identity", token=ptok, json_body={
            "realName": "测试主理人", "idCard": "110101199001010015",
            "consent": True, "source": "topic_publish"})
    show("publisher/identity", json.dumps(status, ensure_ascii=False)[:180])

    step("建期(探店日 FREE_EXPLORE,4 个必选章)")
    # V2 是章节故事块的真实写入口；每章用一段介绍 + 一个节点块，后端负责把 nodeIndex
    # 翻译成真实 nodeId 并物化 blocksJson，开售快照直接回读这份事实。
    chapters = [{"name": "第%d章 %s" % (i + 1, n),
                 "description": "本章一店(%s):进店按规则完成一次到店体验" % n,
                 "schemaVersion": 1, "required": 1,
                 # #299(owner 2026-10-20):探店日章节建期即开放商家承接(同发布向导的章节招商开关)，
                 # 站点玩法由入驻商家提供，因此零玩法也能提交上架；条款与 chain_merchant_club 后台复设同值。
                 # 核验方式/探索值上限由 chain_merchant_club 走后台 supply-terms 设置(建期接口不收 QR 写法)。
                 "recruitEnabled": 1, "categoryId": 4001, "termsMode": "PERK", "perkMinValue": 30,
                 "maxMerchant": 1,
                 "blocks": [
                     {"type": "text", "content": "本章一店(%s):进店按规则完成一次到店体验" % n},
                     {"type": "node", "nodeIndex": 0}],
                 "nodes": [
        {"name": "%s打卡点" % n, "address": "上海市黄浦区%s" % n,
         # ★ NodeDTO 的坐标字段是 longitude/latitude 且是 String;传 lng/lat 数字会让
         #   DistanceCalculatorUtils.calculateDistance 对 null 调 Double.parseDouble 抛 NPE,
         #   最终只回一句「主题发布失败,请稍后重试」(见报告死按钮清单)。
         "longitude": "%.6f" % (121.47 + i * 0.01), "latitude": "%.6f" % (31.23 + i * 0.01),
         "nodeTime": 30}]}
        for i, n in enumerate(["咖啡", "书店", "花店", "面包"])]
    dto = {
        "name": "E2E 探店日一期", "subtitle": "端到端", "description": "5-1 链路真跑",
        "startDate": d(10), "endDate": d(40), "saleEndAt": d(30),
        "categoryIds": "1",
        "productType": 2, "publishMode": "normal",
        "recruitDeadline": d(5), "openMerchantPool": 1,   # 开招商需俱乐部主理人身份(resolveMerchantPoolFlag:2350);建期时先不开,转交俱乐部后再开
        "chapters": chapters, "tickets": [],
    }
    dto.update(club_publish_fields())
    dto.update(fixture_topic_image_fields())
    r = http("POST", "/api/topic/v2/create", token=ptok, json_body=dto)
    show("POST /api/topic/v2/create", json.dumps(r, ensure_ascii=False)[:220])
    tid = r.get("data") if isinstance(r.get("data"), int) else (r.get("topicId") or (r.get("data") or {}).get("id") if isinstance(r.get("data"), dict) else None)
    if not tid:
        # ⚠️ 不按名字去库里捡「最近一条同名主题」当结果:建期失败时那会捡到上一轮的旧主题,
        #    后面每一步都在旧数据上跑出一串漂亮的绿 —— 假绿。建不出来就当场停。
        print("   ✗ 建期未返回 topicId,原始响应见上;不按名字回捡旧主题,链路在此中断")
        return 1
    show("topicId", tid)
    ownership = readback("发布归属(member_id/club_id)",
                         "SELECT member_id,club_id FROM cms_topic WHERE id=%s", (tid,))
    try:
        assert_topic_club_ownership(ownership)
    except RuntimeError as exc:
        print("   ✗ %s；链路在建期后立即中断" % exc)
        return 1
    readback("lifecycle/status", "SELECT id,lifecycle,status,product_type,merchant_status FROM cms_topic WHERE id=%s", (tid,))
    readback("章节数", "SELECT COUNT(*) FROM cms_topic_chapter WHERE topic_id=%s", (tid,))
    readback("节点数", "SELECT COUNT(*) FROM cms_topic_node WHERE topic_id=%s", (tid,))

    step("立项审(create 已自动提交 ⇒ 这里只回读,不重复提交)")
    show("说明", "create 直接把 lifecycle 落到 7=PENDING_REVIEW 并建了审核任务")
    readback("lifecycle", "SELECT lifecycle FROM cms_topic WHERE id=%s", (tid,))
    readback("审核任务", "SELECT id,biz_type,biz_id,status FROM audit_task WHERE biz_id=%s ORDER BY id DESC LIMIT 3", (tid,))

    step("审核通过")
    # ★两个任务都要审:TOPIC_PROJECT_REVIEW 推 lifecycle 7→1,TOPIC 才置 status/user_status=1
    #   只审后者会让「商家承接闸」(要求 status=1 且 userStatus=1)永远过不去。
    rows = q("SELECT id,biz_type FROM audit_task WHERE biz_id=%s AND status=0 ORDER BY id", (tid,))
    for task_id, biz_type in rows:
        r = http("POST", "/business/audit/review", token=atok,
                 json_body={"taskId": task_id, "status": 1, "reasonCode": "E2E", "reasonText": "端到端放行"})
        show("review %s(%s)" % (task_id, biz_type), (r.get("msg") or "")[:40])
    state = readback("status/user_status/lifecycle",
                     "SELECT status,user_status,lifecycle FROM cms_topic WHERE id=%s", (tid,))
    if not state or state[0] != (1, 1, 1):
        print("   ✗ 内容审核或立项审核未闭环,链路在此中断")
        return 1

    # ★ 不做 transfer-to-club:它是【复制一份新主题给俱乐部并把原主题下架】
    #   (CmsTopicServiceImpl.transferToClub:398-421),会把刚过审上架的这一期 user_status 打回 0,
    #   商家承接闸随即恒红。而 H2 的「必须转交俱乐部」闸只对【商家 member 发布的】FREE_EXPLORE 生效
    #   (MerchantPublishedTopicTransferGate.isMerchantPublishedFreeExplore:37-47),
    #   本期发布者是俱乐部主理人 9003、不是商家 ⇒ 该闸对它是 no-op,不需要转交。
    #   2026-08-18 实测:照原样转交后 990014 被下架、四章承接全红「主题未上架或未过审」。

    step("开售审状态闸负控(当前尚未进入 PRICING)")
    r = http("POST", "/business/audit/topic/%d/sale-submit" % tid, token=atok)
    show("sale-submit", json.dumps(r, ensure_ascii=False)[:260])
    state = readback("lifecycle/selling_started_at", "SELECT lifecycle,selling_started_at FROM cms_topic WHERE id=%s", (tid,))
    snapshots = readback("开售快照", "SELECT COUNT(*) FROM topic_sale_snapshot WHERE topic_id=%s", (tid,))
    try:
        show("状态闸", assert_expected_sale_block(r, state, snapshots))
    except RuntimeError as exc:
        print("   ✗ %s" % exc)
        return 1
    print("\nTOPIC_ID=%s" % tid)
    return 0


if __name__ == "__main__":
    sys.exit(main())
