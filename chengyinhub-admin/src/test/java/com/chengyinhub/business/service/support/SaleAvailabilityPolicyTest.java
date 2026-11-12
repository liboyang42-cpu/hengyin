package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 合同二闸3：售卖只认主题实际开售时刻和半开售卖窗，不再借用 ticket.startTime。 */
class SaleAvailabilityPolicyTest {

    @Test
    void pendingUnknownAndNullLifecycleFailClosedWhileSellingPasses() {
        for (Integer lifecycle : new Integer[]{
                TopicLifecycleState.PENDING_REVIEW.getCode(), 99, null}) {
            CmsTopic topic = sellingTopic();
            topic.setLifecycle(lifecycle);
            assertThrows(IllegalStateException.class,
                    () -> SaleAvailabilityPolicy.assertSellable(
                            topic, null, at("2026-08-11T02:30:00Z")));
        }

        assertDoesNotThrow(() -> SaleAvailabilityPolicy.assertSellable(
                sellingTopic(), null, at("2026-08-11T02:30:00Z")));
    }

    private static final Date SELLING_STARTED_AT = at("2026-08-11T01:00:00Z");
    private static final Date TOPIC_SALE_END_AT = at("2026-08-11T04:00:00Z");

    @Test
    void ticketSkuUsesEarlierSignupDeadlineAndRejectsExactBoundary() {
        CmsTopic topic = sellingTopic();
        OmsTicket ticket = ticket(at("2026-08-11T03:00:00Z"));

        assertDoesNotThrow(() -> SaleAvailabilityPolicy.assertSellable(topic, ticket, at("2026-08-11T02:59:59Z")));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> SaleAvailabilityPolicy.assertSellable(topic, ticket, at("2026-08-11T03:00:00Z")));
        assertTrue(failure.getMessage().contains("saleEndAt"), "闸3必须在有效售止相等时关闭");
    }

    @Test
    void ticketWithoutEarlyDeadlineFallsBackToTopicHardSaleEnd() {
        CmsTopic topic = sellingTopic();
        OmsTicket ticket = ticket(null);

        assertDoesNotThrow(() -> SaleAvailabilityPolicy.assertSellable(topic, ticket, at("2026-08-11T03:59:59Z")));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> SaleAvailabilityPolicy.assertSellable(topic, ticket, TOPIC_SALE_END_AT));
        assertTrue(failure.getMessage().contains("saleEndAt"), "空的票种提前售止必须回落主题硬售止");
    }

    @Test
    void selfPlayPassHasNoTicketButStillClosesAtTopicHardSaleEnd() {
        CmsTopic topic = sellingTopic();

        assertDoesNotThrow(() -> SaleAvailabilityPolicy.assertSellable(topic, null, at("2026-08-11T03:59:59Z")));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> SaleAvailabilityPolicy.assertSellable(topic, null, TOPIC_SALE_END_AT));
        assertTrue(failure.getMessage().contains("saleEndAt"), "无票自玩不能绕过主题硬售止");
    }

    @Test
    void freeExploreMaySellAfterTicketFulfillmentStartsUntilHardSaleEnd() {
        CmsTopic topic = sellingTopic();
        topic.setProductType(com.chengyinhub.common.constant.ProductType.FREE_EXPLORE);
        OmsTicket ticket = ticket(null);
        ticket.setStartTime(at("2026-08-11T02:00:00Z"));
        ticket.setEndTime(at("2026-08-11T05:00:00Z"));

        assertDoesNotThrow(() -> SaleAvailabilityPolicy.assertSellable(topic, ticket, at("2026-08-11T03:30:00Z")),
                "自由探索的履约已开始不等于售卖关闭，唯一边界仍是 effectiveSaleEndAt");
    }

    @Test
    void sellingLifecycleAndPersistedStartTimeAreRequired() {
        CmsTopic topic = sellingTopic();
        topic.setLifecycle(TopicLifecycleState.PRICING.getCode());
        IllegalStateException stateFailure = assertThrows(IllegalStateException.class,
                () -> SaleAvailabilityPolicy.assertSellable(topic, null, at("2026-08-11T02:00:00Z")));
        assertTrue(stateFailure.getMessage().contains("lifecycle"), "非 SELLING 必须由 lifecycle 闸拦截");

        topic.setLifecycle(TopicLifecycleState.SELLING.getCode());
        topic.setSellingStartedAt(null);
        IllegalStateException startFailure = assertThrows(IllegalStateException.class,
                () -> SaleAvailabilityPolicy.assertSellable(topic, null, at("2026-08-11T02:00:00Z")));
        assertTrue(startFailure.getMessage().contains("sellingStartedAt"), "缺开售事实必须被 sellingStartedAt 闸拦截");
    }

    /**
     * 「为什么还不能卖」只有一份:非抛孪生返回的每一条,必须逐字等于售卖闸抛出的那句。
     * 作者端状态投影读孪生,下单走抛出版本;两者一旦分叉,作者看到的理由就是假的。
     *
     * <p>负控:把 sellableBlockReason 里任意一条文案改一个字,本用例红。
     */
    @Test
    void nonThrowingReasonIsWordForWordTheGateMessage() {
        CmsTopic nullLifecycle = sellingTopic();
        nullLifecycle.setLifecycle(null);
        assertSameReason(nullLifecycle, null, at("2026-08-11T02:00:00Z"));

        CmsTopic notSelling = sellingTopic();
        notSelling.setLifecycle(TopicLifecycleState.PRICING.getCode());
        assertSameReason(notSelling, null, at("2026-08-11T02:00:00Z"));

        CmsTopic missingStart = sellingTopic();
        missingStart.setSellingStartedAt(null);
        assertSameReason(missingStart, null, at("2026-08-11T02:00:00Z"));

        CmsTopic missingEnd = sellingTopic();
        missingEnd.setSaleEndAt(null);
        assertSameReason(missingEnd, null, at("2026-08-11T02:00:00Z"));

        CmsTopic notStartedYet = sellingTopic();
        assertSameReason(notStartedYet, null, at("2026-08-11T00:30:00Z"));

        assertSameReason(sellingTopic(), null, TOPIC_SALE_END_AT);
        assertSameReason(sellingTopic(), ticket(TOPIC_SALE_END_AT), TOPIC_SALE_END_AT);

        assertNull(SaleAvailabilityPolicy.sellableBlockReason(sellingTopic(), null, at("2026-08-11T02:00:00Z")),
                "可售时必须返回 null,不能编一条理由");
    }

    /** 同一对入参:孪生返回的 reason 必须逐字等于 assertSellable 抛出的那句话。 */
    private static void assertSameReason(CmsTopic topic, OmsTicket ticket, Date now) {
        String reason = SaleAvailabilityPolicy.sellableBlockReason(topic, ticket, now);
        assertNotNull(reason, "这一组入参应当被售卖闸拒绝");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> SaleAvailabilityPolicy.assertSellable(topic, ticket, now));
        assertEquals(reason, failure.getMessage());
    }

    private static CmsTopic sellingTopic() {
        CmsTopic topic = new CmsTopic();
        topic.setLifecycle(TopicLifecycleState.SELLING.getCode());
        topic.setSellingStartedAt(SELLING_STARTED_AT);
        topic.setSaleEndAt(TOPIC_SALE_END_AT);
        return topic;
    }

    private static OmsTicket ticket(Date signupDeadline) {
        OmsTicket ticket = new OmsTicket();
        ticket.setSignupDeadline(signupDeadline);
        return ticket;
    }

    private static Date at(String instant) {
        return Date.from(Instant.parse(instant));
    }
}
