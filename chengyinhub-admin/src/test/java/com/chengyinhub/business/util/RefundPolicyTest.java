package com.chengyinhub.business.util;

import com.chengyinhub.business.util.RefundPolicy.Verdict;
import com.chengyinhub.common.constant.PurchaseKind;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RefundPolicy} 可退判定单测(整改报告 2026-06-30 口径)。
 * 纯逻辑,无 Spring/DB 依赖。
 */
class RefundPolicyTest {

    private static final long H = 3600_000L;
    private final Date now = new Date(1_700_000_000_000L);
    private final Date past = new Date(now.getTime() - 24 * H);
    private final Date future = new Date(now.getTime() + 24 * H);

    @Test
    void cityOrienteering_beforeMeeting_refundable() {
        Verdict v = RefundPolicy.evaluate(1L, 2, 0, 1, future, null, null, now);
        assertTrue(v.refundable);
        assertEquals(future, v.deadline);
    }

    @Test
    void cityOrienteering_afterMeeting_notRefundable() {
        Verdict v = RefundPolicy.evaluate(1L, 2, 0, 1, past, null, null, now);
        assertFalse(v.refundable);
        assertTrue(v.reason.contains("集合时间"));
    }

    @Test
    void freeExplore_beforeValidEnd_refundable() {
        Verdict v = RefundPolicy.evaluate(1L, 2, 0, 2, null, future, null, now);
        assertTrue(v.refundable);
    }

    @Test
    void freeExplore_afterValidEnd_notRefundable() {
        Verdict v = RefundPolicy.evaluate(1L, 2, 0, 2, null, past, null, now);
        assertFalse(v.refundable);
        assertTrue(v.reason.contains("有效期"));
    }

    @Test
    void activity_beforeStart_refundable() {
        Verdict v = RefundPolicy.evaluate(2L, 2, 0, null, null, null, future, now);
        assertTrue(v.refundable);
    }

    @Test
    void activity_afterStart_notRefundable() {
        Verdict v = RefundPolicy.evaluate(2L, 2, 0, null, null, null, past, now);
        assertFalse(v.refundable);
        // 5.9 文案:不再是「已错过活动开始时间」——场次票的窗口是集合时间前 24h。
        assertTrue(v.reason.contains("集合前24小时"), "实际:" + v.reason);
    }

    @Test
    void freeSessionCanReleaseCapacityWithin24HoursButNotAtStartOrAfterVerification() {
        Date start = new Date(2000000000000L);
        Date cutoff = RefundPolicy.sessionRefundDeadline(start);
        Date justBefore = new Date(start.getTime() - 1L);
        assertTrue(RefundPolicy.evaluate(2L, 2, 0, null, null, null, start, null, cutoff,
                java.math.BigDecimal.ZERO, 0L, justBefore).refundable);
        assertFalse(RefundPolicy.evaluate(2L, 2, 0, null, null, null, start, null, cutoff,
                java.math.BigDecimal.ZERO, 0L, start).refundable);
        assertFalse(RefundPolicy.evaluate(2L, 2, 1, null, null, null, start, null, cutoff,
                java.math.BigDecimal.ZERO, 0L, justBefore).refundable);
        assertFalse(RefundPolicy.evaluate(2L, 2, 0, null, null, null, start, null, cutoff,
                java.math.BigDecimal.ONE, 0L, justBefore).refundable);
        assertFalse(RefundPolicy.evaluate(2L, 2, 0, null, null, null, start, null, cutoff,
                java.math.BigDecimal.ZERO, 10L, justBefore).refundable);
        assertFalse(RefundPolicy.evaluate(2L, 2, 0, null, null, null, start, null, cutoff,
                java.math.BigDecimal.ZERO, null, justBefore).refundable, "积分快照缺失不能当作免费票");
        assertFalse(RefundPolicy.evaluate(2L, 2, 0, null, null, null, null, null, cutoff,
                java.math.BigDecimal.ZERO, 0L, justBefore).refundable);
    }

    @Test
    void verified_notRefundable_evenInWindow() {
        Verdict v = RefundPolicy.evaluate(1L, 2, 1, 1, future, null, null, now);
        assertFalse(v.refundable);
        assertTrue(v.reason.contains("核销"));
    }

    @Test
    void unpaid_notRefundable() {
        Verdict v = RefundPolicy.evaluate(1L, 1, 0, 1, future, null, null, now);
        assertFalse(v.refundable);
        assertTrue(v.reason.contains("未支付"));
    }

    /**
     * [P0-05] 口径反转:截止时间缺失 ⇒ **不自动退,转人工**。
     *
     * <p>本用例原名 missingDeadline_conservativelyRefundable、断言 refundable=true —— 那是把 bug
     * 写成了断言。「保守=放行」方向反了:截止时间缺失不是罕见异常,而是发布器的常态产物
     * (mode=1 票 start_time 可为 null / mode=2 票 end_time 可为 null),于是这条「保守」等价于
     * 那些票**永远无条件全额可退**,玩完再退也照退。三条「永远可退」里的两条从这里漏出去。
     */
    @Test
    void missingDeadline_notAutoRefundable_goesManual() {
        Verdict v = RefundPolicy.evaluate(1L, 2, 0, 1, null, null, null, now);
        assertFalse(v.refundable, "算不出截止点时不得自动退 —— 系统没资格替业务决定");
        assertTrue(v.manualReview, "必须标转人工:这不是「不该退」,是「算不出来」,两者别混");
        assertNull(v.deadline);
        assertTrue(v.reason.contains("人工"), "理由要能让客服看懂,实际:" + v.reason);
    }

    /** 「规则上不该退」不该被误标成转人工 —— 那会把人工队列淹了。 */
    @Test
    void missedDeadline_notRefundable_butNotManual() {
        Verdict v = RefundPolicy.evaluate(1L, 2, 0, 1, past, null, null, now);
        assertFalse(v.refundable);
        assertFalse(v.manualReview, "错过窗口是终局判定,不用人管");
    }

    /** 已核销同理:终局,不转人工。 */
    @Test
    void verified_notRefundable_butNotManual() {
        Verdict v = RefundPolicy.evaluate(1L, 2, 1, 1, future, null, null, now);
        assertFalse(v.refundable);
        assertFalse(v.manualReview);
    }

    /** 自由定向(mode=2)缺 end_time ⇒ 同样转人工(G3 那个洞的兜底)。 */
    @Test
    void freeExploreMissingEndTime_goesManual() {
        Verdict v = RefundPolicy.evaluate(1L, 2, 0, 2, null, null, null, now);
        assertFalse(v.refundable);
        assertTrue(v.manualReview);
    }

    /** 活动缺 start_date ⇒ 同样转人工。 */
    @Test
    void activityMissingStartDate_goesManual() {
        Verdict v = RefundPolicy.evaluate(2L, 2, 0, null, null, null, null, now);
        assertFalse(v.refundable);
        assertTrue(v.manualReview);
    }

    /** 正常路径不受影响:有截止点且未过 ⇒ 可退,且不该标人工。 */
    @Test
    void normalRefundable_notManual() {
        Verdict v = RefundPolicy.evaluate(1L, 2, 0, 1, future, null, null, now);
        assertTrue(v.refundable);
        assertFalse(v.manualReview);
    }

    @Test
    void exactlyAtDeadline_stillRefundable() {
        Verdict v = RefundPolicy.evaluate(1L, 2, 0, 1, now, null, null, now);
        assertTrue(v.refundable);
    }

    // ────────────────────────────────────────────────────────────────────────
    // 5.9 场次票(ownerType=2):集合时间前 24 小时可全额退,之后不退。
    // 产品拍板 2026-09-15(35 问 #1)。anchor = cms_activity.start_date(集合时间)。
    // ────────────────────────────────────────────────────────────────────────

    /** 截止点必须 = 集合时间 − 24h,不再是集合时间本身。 */
    @Test
    void session_deadlineIs24hBeforeAssembly() {
        Date assembly = new Date(now.getTime() + 72 * H);
        Verdict v = RefundPolicy.evaluate(2L, 2, 0, null, null, null, assembly, now);
        assertTrue(v.refundable);
        assertEquals(new Date(assembly.getTime() - 24 * H), v.deadline,
                "场次票截止点必须是集合时间前 24 小时");
    }

    /**
     * 存量场次(start_date 落当日 00:00,集合时刻改造之前创建)按现实现计算不变:
     * 截止仍是「前一日 00:00」,不回溯改写 —— 改造只约束新开场必须带时刻。
     */
    @Test
    void session_legacyMidnightAssembly_keepsTheOldCutoff() {
        java.util.Calendar assembly = java.util.Calendar.getInstance(
                java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        assembly.clear();
        assembly.set(2027, java.util.Calendar.MARCH, 8, 0, 0, 0);

        java.util.Calendar beforeCutoff = java.util.Calendar.getInstance(
                java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        beforeCutoff.clear();
        beforeCutoff.set(2027, java.util.Calendar.MARCH, 6, 23, 0, 0);

        java.util.Calendar afterCutoff = java.util.Calendar.getInstance(
                java.util.TimeZone.getTimeZone("Asia/Shanghai"));
        afterCutoff.clear();
        afterCutoff.set(2027, java.util.Calendar.MARCH, 7, 12, 0, 0);

        assertTrue(RefundPolicy.evaluate(2L, 2, 0, null, null, null,
                assembly.getTime(), beforeCutoff.getTime()).refundable,
                "存量 00:00 场次在前一日 00:00 之前仍可退");
        assertFalse(RefundPolicy.evaluate(2L, 2, 0, null, null, null,
                assembly.getTime(), afterCutoff.getTime()).refundable,
                "存量 00:00 场次过了前一日 00:00 仍不可退(不回溯改写)");
    }

    /** 恰好集合前 24h(now == 截止点)⇒ 仍在窗口内(半开区间,相等即关闭的反面)。 */
    @Test
    void session_exactly24hBeforeAssembly_refundable() {
        Date assembly = new Date(now.getTime() + 24 * H);
        Verdict v = RefundPolicy.evaluate(2L, 2, 0, null, null, null, assembly, now);
        assertTrue(v.refundable, "恰好 24h 应仍可退");
        assertEquals(now, v.deadline);
    }

    /** 24h 截止点前 1 分钟(now 比截止点早 1 分钟)⇒ 可退。 */
    @Test
    void session_1minBeforeCutoff_refundable() {
        Date assembly = new Date(now.getTime() + 24 * H + 60_000L);
        Verdict v = RefundPolicy.evaluate(2L, 2, 0, null, null, null, assembly, now);
        assertTrue(v.refundable, "离截止点还有 1 分钟,应可退");
    }

    /** 过了 24h 截止点 1 分钟 ⇒ 不可退(规则终局,不是「算不出来」)。 */
    @Test
    void session_1minAfterCutoff_notRefundable() {
        Date assembly = new Date(now.getTime() + 24 * H - 60_000L);
        Verdict v = RefundPolicy.evaluate(2L, 2, 0, null, null, null, assembly, now);
        assertFalse(v.refundable, "过了集合前 24h 应不可退");
        assertFalse(v.manualReview, "超过窗口是终局判定,不该转人工");
        assertEquals(new Date(assembly.getTime() - 24 * H), v.deadline);
    }

    /** 无集合时间(start_date 缺失)⇒ fail-closed:不自动退、转人工并给提示。 */
    @Test
    void session_missingAssemblyTime_goesManualWithHint() {
        Verdict v = RefundPolicy.evaluate(2L, 2, 0, null, null, null, null, now);
        assertFalse(v.refundable, "算不出集合时间不得自动退");
        assertTrue(v.manualReview, "缺锚点必须转人工");
        assertNull(v.deadline);
        assertTrue(v.reason.contains("人工"), "理由要能让玩家看懂,实际:" + v.reason);
    }

    /**
     * [退款转人工-不再静默] 所有 manualReview=true 的拒绝文案都必须给出**下一步**。
     *
     * <p>为什么单开一条:{@code manualReview} 表示「算不出来 / 要人判」,而它背后曾经**没有队列**
     * (P0-05 自注「只是一句更诚实的话」)。一句「需人工处理」不告诉用户找谁,等于把请求丢在地板上 ——
     * H 套审查里「只能找客服」19 条的典型根因。真队列没拍板前,先把出口指对:联系客服。
     */
    @Test
    void manualReviewEveryReason_carriesNextStep() {
        Verdict missingDeadline = RefundPolicy.evaluate(1L, 2, 0, 1, null, null, null, now);
        Verdict missingWindow = RefundPolicy.evaluateTopicFulfillmentWindow(2, 0, null, null, now);
        Verdict verifiedExplore = RefundPolicy.verifiedVerdict(PurchaseKind.EXPLORE_PASS, 1);

        Verdict[] all = {missingDeadline, missingWindow, verifiedExplore};
        for (Verdict v : all) {
            assertTrue(v.manualReview, "这条路径必须标转人工");
            assertTrue(v.reason.contains("人工"), "保留「人工」口径,实际:" + v.reason);
            assertTrue(v.reason.contains("联系客服"),
                    "转人工的文案必须告诉用户下一步(联系客服),实际:" + v.reason);
        }
    }

    /**
     * 1-22 附注(拍板 2026-09-19 第6条):报价侧下单前预判定,读的必须是同一口场次钟。
     * 公开 sessionRefundDeadline 是唯一切口 —— 这里钉死 值=集合时间−24h、缺锚点=null,
     * 防止有人把「减 24h」再抄一份到 quote/前端路径。
     */
    @Test
    void sessionRefundDeadline_publicClock_isAssemblyMinus24h() {
        Date assembly = new Date(now.getTime() + 30 * H);
        Date deadline = RefundPolicy.sessionRefundDeadline(assembly);
        assertEquals(assembly.getTime() - RefundPolicy.SESSION_REFUND_WINDOW_MS, deadline.getTime());
        assertNull(RefundPolicy.sessionRefundDeadline(null), "缺锚点原样 null,由上层转人工");
        // 与 evaluate 的场次分支同钟:集合前 23h 下单 ⇒ 钟已过,报价侧必须回 false
        Date in23h = new Date(now.getTime() + 23 * H);
        assertFalse(!now.after(RefundPolicy.sessionRefundDeadline(in23h)),
                "集合前 23 小时已过退款截止,报价侧不可判成可退");
    }

    /** F-W5-07:免费场次单(0 元且未用积分)在集合前 24h 内仍可取消并释放名额,开场后才关闭。 */
    @Test
    void freeSessionOrder_within24h_stillCancellableUntilStart() {
        Date in2h = new Date(now.getTime() + 2 * H);
        Verdict v = RefundPolicy.evaluate(2L, 2, 0, null, null, null, in2h, null, null,
                java.math.BigDecimal.ZERO, 0L, now);
        assertTrue(v.refundable, "免费单集合前 2 小时仍应可取消报名");
        assertEquals(in2h, v.deadline);

        Verdict started = RefundPolicy.evaluate(2L, 2, 0, null, null, null, past, null, null,
                java.math.BigDecimal.ZERO, 0L, now);
        assertFalse(started.refundable, "开场后免费单也不可取消");

        Verdict verified = RefundPolicy.evaluate(2L, 2, 1, null, null, null, in2h, null, null,
                java.math.BigDecimal.ZERO, 0L, now);
        assertFalse(verified.refundable, "已核销免费单不可取消");
    }

    /** F-W5-07 负控:付费或用了积分的场次单仍守集合前 24h。 */
    @Test
    void paidOrPointSessionOrder_within24h_notRefundable() {
        Date in2h = new Date(now.getTime() + 2 * H);
        assertFalse(RefundPolicy.evaluate(2L, 2, 0, null, null, null, in2h, null, null,
                new java.math.BigDecimal("9.90"), 0L, now).refundable);
        assertFalse(RefundPolicy.evaluate(2L, 2, 0, null, null, null, in2h, null, null,
                java.math.BigDecimal.ZERO, 100L, now).refundable);
    }
}
