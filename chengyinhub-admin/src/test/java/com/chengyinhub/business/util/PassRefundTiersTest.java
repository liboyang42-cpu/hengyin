package com.chengyinhub.business.util;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.common.constant.PurchaseKind;
import com.chengyinhub.common.constant.RefundPolicyCode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 2026-11-03 业主:② 自玩通行证与 ③ 自由探索票与场次票同三档(100/50/0),钟 = 履约窗开始,下单冻结。
 * 存量单认自己当初的口径,不收回已答应的退款。
 */
class PassRefundTiersTest {

    private static final long H = 3600_000L;
    private static final ZoneId SH = ZoneId.of("Asia/Shanghai");
    private static final Date START = new Date(1_800_000_000_000L);
    private static final Date END = new Date(START.getTime() + 72 * H);

    private static Date before(long millis) { return new Date(START.getTime() - millis); }

    private static RefundPolicy.Verdict explore(String code, Date snapshot, Date now) {
        return RefundPolicy.evaluate(2L, 2, 0, 2, null, null, /* 场次开始,与 ③ 的钟无关 */ END,
                PurchaseKind.EXPLORE_PASS, snapshot, code, new BigDecimal("30.00"), 0L, now);
    }

    @Test
    void exploreSnapshotFreezesTicketStartWithTieredCode() {
        RefundPolicy.Snapshot s = RefundPolicy.snapshotOnPurchase(PurchaseKind.EXPLORE_PASS, START, END);
        assertEquals(RefundPolicyCode.START_24H_100_50_0, s.code);
        assertEquals(START, s.deadline, "三档的锚 = 履约窗开始(票的 start_time)");
    }

    @Test
    void explorePassTiers() {
        String code = RefundPolicyCode.START_24H_100_50_0;
        RefundPolicy.Verdict early = explore(code, START, before(25 * H));
        assertTrue(early.refundable);
        assertFalse(early.partial());
        assertEquals(before(24 * H), early.deadline);

        RefundPolicy.Verdict late = explore(code, START, before(2 * H));
        assertTrue(late.refundable);
        assertTrue(late.partial(), "开始前 24 小时内 ⇒ 50%");
        assertEquals(0, new BigDecimal("0.50").compareTo(late.ratio));

        RefundPolicy.Verdict started = explore(code, START, new Date(START.getTime() + 60_000L));
        assertFalse(started.refundable);
        assertFalse(started.manualReview);
        assertEquals("已开始，不可退款", started.reason);
    }

    /** 存量 ③ 单(EXPLORE_END_100_0)当初答应「有效期截止前全额」:开始后、截止前仍全额,不追溯。 */
    @Test
    void legacyExploreSnapshotKeepsItsPromise() {
        RefundPolicy.Verdict v = explore(RefundPolicyCode.EXPLORE_END_100_0, END, new Date(START.getTime() + H));
        assertTrue(v.refundable);
        assertFalse(v.partial());
        assertEquals(END, v.deadline);
    }

    private static CmsTopic topicFrom(LocalDate start, LocalDate end) {
        CmsTopic t = new CmsTopic();
        t.setStartDate(Date.from(start.atStartOfDay(SH).toInstant()));
        t.setEndDate(Date.from(end.atStartOfDay(SH).toInstant()));
        return t;
    }

    @Test
    void selfPlaySnapshotFreezesTopicWindowStart() {
        LocalDate day = LocalDate.of(2026, 12, 1);
        RefundPolicy.Snapshot s = RefundPolicy.snapshotSelfPlayPass(topicFrom(day, day.plusDays(30)));
        assertEquals(RefundPolicyCode.START_24H_100_50_0, s.code);
        assertEquals(Date.from(day.atStartOfDay(SH).toInstant()), s.deadline);
        assertNull(RefundPolicy.snapshotSelfPlayPass(new CmsTopic()), "履约窗算不出来 ⇒ 不打快照");
        assertNull(RefundPolicy.snapshotSelfPlayPass(null));
    }

    @Test
    void selfPlayPassTiersReadTheFrozenStart() {
        LocalDate day = LocalDate.of(2026, 12, 1);
        Date start = Date.from(day.atStartOfDay(SH).toInstant());
        // 主题开始日事后被改晚:冻结的锚胜出
        CmsTopic live = topicFrom(day.plusDays(5), day.plusDays(30));
        String code = RefundPolicyCode.START_24H_100_50_0;

        RefundPolicy.Verdict early = RefundPolicy.evaluateTopicFulfillmentWindow(2, 0, live, null, code, start,
                new Date(start.getTime() - 30 * H));
        assertTrue(early.refundable);
        assertFalse(early.partial());

        RefundPolicy.Verdict late = RefundPolicy.evaluateTopicFulfillmentWindow(2, 0, live, null, code, start,
                new Date(start.getTime() - 3 * H));
        assertTrue(late.partial());
        assertEquals(0, new BigDecimal("0.50").compareTo(late.ratio));

        RefundPolicy.Verdict after = RefundPolicy.evaluateTopicFulfillmentWindow(2, 0, live, null, code, start,
                new Date(start.getTime() + H));
        assertFalse(after.refundable);
    }

    /** 存量 ② 单无快照:当初答应「履约窗开始前全额」,24 小时内仍全额。 */
    @Test
    void legacySelfPlayPassWithoutSnapshotKeepsFullRefundUntilStart() {
        LocalDate day = LocalDate.of(2026, 12, 1);
        CmsTopic topic = topicFrom(day, day.plusDays(30));
        Date start = Date.from(day.atStartOfDay(SH).toInstant());
        RefundPolicy.Verdict v = RefundPolicy.evaluateTopicFulfillmentWindow(2, 0, topic, null, null, null,
                new Date(start.getTime() - 3 * H));
        assertTrue(v.refundable);
        assertFalse(v.partial());
    }

    /** 主题路径的 ③ 票(owner_type=1,有票)新单同样按票的开始时间三档。 */
    @Test
    void topicExploreTicketWithTieredSnapshot() {
        OmsTicket ticket = new OmsTicket();
        ticket.setStartTime(START);
        ticket.setEndTime(END);
        RefundPolicy.Verdict v = RefundPolicy.evaluateTopicFulfillmentWindow(2, 0, new CmsTopic(), ticket,
                RefundPolicyCode.START_24H_100_50_0, START, before(H));
        assertTrue(v.partial());
    }
}
