package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.enums.TopicLifecycleState;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
