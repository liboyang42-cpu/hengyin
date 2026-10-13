package com.chengyinhub.business.util;

import com.chengyinhub.common.constant.PurchaseKind;
import com.chengyinhub.common.constant.RefundPolicyCode;
import org.junit.jupiter.api.Test;

import java.util.Calendar;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0-06 退款政策快照 + ③ 的档位。
 *
 * <p><b>③ 的档位口径</b>(施工图 §4 逐字):「③ 锚 oms_ticket.end_time 截止前且未核销 100% /
 * 首核销转人工 / 截止后 0%」—— **两档,没有 30%**。30% 是 ① 专属,而 ① 不在首轮(D12)。
 *
 * <p><b>两条病</b>:
 * <ol>
 *   <li><b>现取锚点 = 追溯改写已售单</b>。evaluate 每次现取票的 end_time,而 oms_ticket 卖了票之后
 *       后台还能改 ⇒ 运营把 end_time 往前挪,所有已售单的退款窗口被凭空砍掉,零告警。
 *       与 P0-28 费率快照同一个病根:成交时固化的是结果,判定时却现取输入。</li>
 *   <li><b>③ 的已核销硬拒</b>。「已核销」≠「不该退」——误扫/权益缺口/商家没兑现都是核销后仍该退的真实场景。
 *       硬拒把这些人挡在门外且无人知晓;正确做法是转人工。<b>⚠️ 只改 ③</b> —— ①/活动 的已核销是终局
 *       (人真被带出去/真去参加了),不在本轮授权内,行为原样不动(见 verified_manualOnlyForExplorePass)。</li>
 * </ol>
 *
 * <p>⚠️ <b>{@code manualReview} 长期被丢弃</b> —— ③ 已核销那一档有运营覆盖读方
 * ({@code RefundServiceImpl#ensureRefundable}),而「截止时间缺失」那一档直到 2026-09-16 仍没有真队列,
 * 只做到文案指出下一步 + 读侧留 {@code [退款转人工]} 日志(见 RefundPolicyTest/RefundDeadlineGateTest
 * 的新用例)。「转人工」仍不是一个已经通了的人工兜底,别把这里绿当成兜底存在。
 *
 * ⚠️ 负控已实测(注入→红→撤销→绿):
 *  - evaluate 里 snapshotDeadline 分支删掉(改回恒 resolveDeadline)⇒ snapshot_winsOverLive* 红
 *  - snapshotOnPurchase 的 EXPLORE_PASS 判断放宽成「非 null 就快照」⇒ snapshot_onlyForExplorePass 红
 *  - ③ 闸改成 if(true)(转人工又变全局)⇒ verified_manualOnlyForExplorePass 红
 *  - ③ 闸改成 if(false)(③ 也不转人工)⇒ verified_goesToManualReview 红
 *  - 下单删掉快照整段 / 读侧把快照换回 null ⇒ RefundSnapshotWiringTest 红
 */
class RefundPolicySnapshotTest {

    private static Date at(int year, int month, int day) {
        Calendar c = Calendar.getInstance();
        c.clear();
        c.set(year, month - 1, day);
        return c.getTime();
    }

    private static final Date JUL_20 = at(2026, 7, 20);
    private static final Date JUL_18 = at(2026, 7, 18);
    private static final Date JUL_16 = at(2026, 7, 16);
    private static final Date JUL_25 = at(2026, 7, 25);

    // ------------------------------------------------------- 下单时:快照打不打

    /** ③ 打快照:code/version/anchor/deadline 四列齐。③ 里 deadline == anchor(没有提前量)。 */
    @Test
    void snapshot_forExplorePass() {
        RefundPolicy.Snapshot s = RefundPolicy.snapshotOnPurchase(PurchaseKind.EXPLORE_PASS, JUL_20);

        assertEquals(RefundPolicyCode.EXPLORE_END_100_0, s.code);
        assertEquals(Integer.valueOf(RefundPolicyCode.CURRENT_VERSION), s.version);
        assertEquals(JUL_20, s.deadline, "③ 的截止点 = 票的 end_time");
    }

    /**
     * **只落 ③**。①② 不打快照 —— 施工图明写「P0-06 只需 ③ 的档位」,
     * 提前给它们冻一个没人验证过的档位,比不冻危险得多(冻错了是永久的,存量单再也改不回来)。
     */
    @Test
    void snapshot_onlyForExplorePass() {
        assertNull(RefundPolicy.snapshotOnPurchase(PurchaseKind.GUIDED_TICKET, JUL_20), "① 不在首轮,不落码");
        assertNull(RefundPolicy.snapshotOnPurchase(PurchaseKind.SELF_PLAY_PASS, JUL_20), "② 依赖 P0-04 的激活态,未落码");
        assertNull(RefundPolicy.snapshotOnPurchase(null, JUL_20), "后台手工建单/独立活动无购买类型可推");
    }

    /**
     * 锚点源本身缺失(G3:mode=2 票的 end_time 可为 null)⇒ **不快照**,不是快照个 null。
     * 留空 ⇒ 读侧回落现取 ⇒ 仍然算不出 ⇒ 走 P0-05 的转人工。捏造一个截止点才是灾难。
     */
    @Test
    void snapshot_nullAnchorSource_noSnapshot() {
        assertNull(RefundPolicy.snapshotOnPurchase(PurchaseKind.EXPLORE_PASS, null));
    }

    // ------------------------------------------------------- 判定时:快照优先(本类心脏)

    /**
     * <b>这条是本类的心脏</b>:快照胜过 live —— 即使票的 end_time 已经被运营改过。
     *
     * <p>场景:玩家 7-16 买票,当时票的有效期是 7-20(「7-20 前可退」)。运营随后把 end_time
     * 改成 7-18。玩家 7-19 来退:按快照 = 还没到 7-20,可退;按 live = 已错过 7-18,拒退。
     * 后者就是**追溯改写已售订单**,玩家买的时候答应的事被单方面撕毁。
     */
    @Test
    void snapshot_winsOverLiveTicketEndTime() {
        RefundPolicy.Verdict v = RefundPolicy.evaluate(
                1L, 2, 0, 2, null, /* live 已被改成 */ JUL_18, null,
                PurchaseKind.EXPLORE_PASS, /* 快照 */ JUL_20, at(2026, 7, 19));

        assertTrue(v.refundable, "必须按买的时候答应的 7-20 算 —— 读 live 的 7-18 就是追溯砍掉玩家的退款窗口");
        assertEquals(JUL_20, v.deadline);
    }

    /** 反向同样成立:live 被往后放宽了,快照仍然管住 —— 别让改一个数就把已结束的窗口重新打开。 */
    @Test
    void snapshot_winsWhenLiveIsMorePermissive() {
        RefundPolicy.Verdict v = RefundPolicy.evaluate(
                1L, 2, 0, 2, null, /* live 放宽到 */ JUL_25, null,
                PurchaseKind.EXPLORE_PASS, /* 快照 */ JUL_16, at(2026, 7, 19));

        assertFalse(v.refundable, "快照说 7-16 截止就是 7-16 截止");
        assertEquals(JUL_16, v.deadline);
    }

    /** 存量单 / 未打快照 ⇒ 回落现取,与快照落地前**行为完全一致**(这正是四列可空的意义)。 */
    @Test
    void noSnapshot_fallsBackToLive() {
        RefundPolicy.Verdict v = RefundPolicy.evaluate(
                1L, 2, 0, 2, null, JUL_20, null, PurchaseKind.EXPLORE_PASS, /* 无快照 */ null, JUL_18);

        assertTrue(v.refundable);
        assertEquals(JUL_20, v.deadline, "存量单读 live 是对的");
    }

    /** 旧 8 参签名 = 无快照,行为不变(它还留着给不带快照上下文的纯算场景)。 */
    @Test
    @SuppressWarnings("deprecation")
    void legacySignature_behavesAsNoSnapshot() {
        RefundPolicy.Verdict v = RefundPolicy.evaluate(1L, 2, 0, 2, null, JUL_20, null, JUL_18);
        assertTrue(v.refundable);
        assertEquals(JUL_20, v.deadline);
    }

    // ------------------------------------------------------- ③ 的三档

    /** 截止前且未核销 ⇒ 100%。 */
    @Test
    void beforeDeadlineUnverified_fullRefund() {
        RefundPolicy.Verdict v = RefundPolicy.evaluate(1L, 2, 0, 2, null, null, null, PurchaseKind.EXPLORE_PASS, JUL_20, JUL_18);

        assertTrue(v.refundable);
        assertFalse(v.manualReview, "正常可退不该占人工队列");
    }

    /** 截止后 ⇒ 0%,且**不转人工**(规则上就不该退,是终局,不用人管)。 */
    @Test
    void afterDeadline_zeroAndNotManual() {
        RefundPolicy.Verdict v = RefundPolicy.evaluate(1L, 2, 0, 2, null, null, null, PurchaseKind.EXPLORE_PASS, JUL_16, JUL_18);

        assertFalse(v.refundable);
        assertFalse(v.manualReview, "「已错过窗口」是规则终局,不是「算不出来」—— 别把它塞进人工队列");
    }

    /**
     * <b>首核销转人工</b>,不是硬拒 —— 这是本次改的行为。
     *
     * <p>核销只证明「码被扫了」,不证明「玩家拿到了他买的东西」:店员误扫、商家没兑现、
     * 权益缺口都会留下一个已核销但该退的单。硬拒 = 这些人退不了且没人知道;
     * 转人工 = 系统承认自己没资格独自决定。
     */
    @Test
    void verified_goesToManualReview() {
        RefundPolicy.Verdict v = RefundPolicy.evaluate(
                1L, 2, /* 已核销 */ 1, 2, null, null, null, PurchaseKind.EXPLORE_PASS, JUL_20, JUL_18);

        assertFalse(v.refundable, "不自动退");
        assertTrue(v.manualReview, "**转人工** —— 已核销不等于不该退");
        assertEquals("已核销订单需人工核实,请联系客服", v.reason);
    }

    /**
     * <b>越界闸</b>:转人工**只对 ③**。施工图只授权了 ③ 的档位,①/活动 的「已核销」是终局
     * (领队真把人带出去了 / 人真去参加了),硬拒是对的。
     *
     * <p>把它们一起翻成「需人工处理」= 给用户假希望,而 {@code manualReview} 背后长期没有真队列接 ——
     * 那是把一句谎话印在退款页上。存量单(purchaseKind=null)同理走原口径。
     */
    @Test
    void verified_manualOnlyForExplorePass() {
        RefundPolicy.Verdict guided = RefundPolicy.evaluate(
                1L, 2, 1, 1, JUL_25, null, null, PurchaseKind.GUIDED_TICKET, null, JUL_18);
        assertFalse(guided.manualReview, "① 不在本轮授权内,行为必须原样不动");
        assertEquals("已核销报名不可退款", guided.reason);

        RefundPolicy.Verdict activity = RefundPolicy.evaluate(
                2L, 2, 1, null, null, null, JUL_25, null, null, JUL_18);
        assertFalse(activity.manualReview, "活动同理");

        RefundPolicy.Verdict legacy = RefundPolicy.evaluate(
                1L, 2, 1, 2, null, null, null, /* 存量单无购买类型 */ null, null, JUL_18);
        assertFalse(legacy.manualReview, "存量单推不出购买类型 ⇒ 回落原口径,别拿它赌");
    }

    /** 已核销即便还在窗口内也不自动退(转人工那条路),窗口不是核销的赦免。 */
    @Test
    void verified_manualEvenBeforeDeadline() {
        RefundPolicy.Verdict v = RefundPolicy.evaluate(1L, 2, 1, 2, null, null, null, PurchaseKind.EXPLORE_PASS, JUL_25, JUL_18);

        assertFalse(v.refundable);
        assertTrue(v.manualReview);
    }

    /** 未支付仍是硬拒、不转人工(没钱进来,没什么可退的)—— 别让它淹了人工队列。 */
    @Test
    void unpaid_hardRejectNotManual() {
        RefundPolicy.Verdict v = RefundPolicy.evaluate(1L, 0, 0, 2, null, null, null, PurchaseKind.EXPLORE_PASS, JUL_20, JUL_18);

        assertFalse(v.refundable);
        assertFalse(v.manualReview);
    }

    /** 快照与 live 双缺 ⇒ P0-05 的「算不出来 ⇒ 转人工」,不能因为快照落地就把这条兜底弄丢了。 */
    @Test
    void noDeadlineAtAll_stillManualReview() {
        RefundPolicy.Verdict v = RefundPolicy.evaluate(1L, 2, 0, 2, null, null, null, PurchaseKind.EXPLORE_PASS, null, JUL_18);

        assertFalse(v.refundable);
        assertTrue(v.manualReview, "P0-05 的兜底不能被 P0-06 顶掉");
    }
}
