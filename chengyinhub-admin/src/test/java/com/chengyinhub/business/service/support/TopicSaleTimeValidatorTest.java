package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.common.constant.ProductType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 合同二 §2.3：非法时间必须指认相邻字段，不得返回笼统“时间错误”。 */
class TopicSaleTimeValidatorTest {

    @Test
    void recruitmentDeadlineMayEqualSellingStartButNotBeMissingForOpenRecruitment() {
        CmsTopic topic = sellableTopic();
        Date start = at("2026-08-11T01:00:00Z");
        topic.setMerchantStatus(1);
        topic.setRecruitDeadline(start);

        assertDoesNotThrow(() -> TopicSaleTimeValidator.assertValid(topic,
                Collections.singletonList(ticket()), start));

        topic.setRecruitDeadline(null);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> TopicSaleTimeValidator.assertValid(topic, Collections.singletonList(ticket()), start));
        assertTrue(failure.getMessage().contains("recruitDeadline"), "开放承接必须点名 recruitDeadline");
    }

    /** P-01：俱乐部发的城市定向在招募期邀来带队俱乐部(阵容非空)时，recruitDeadline 是真实招募截止，不该被拒。 */
    @Test
    void cityThemeThatRecruitedAnOrganizerKeepsItsRecruitDeadline() {
        CmsTopic topic = sellableTopic();
        Date start = at("2026-08-11T01:00:00Z");
        topic.setProductType(com.chengyinhub.common.constant.ProductType.ORIENTEERING);
        topic.setMerchantStatus(0);
        topic.setOpenClubPool(0);
        topic.setRecruitDeadline(start);

        IllegalStateException without = assertThrows(IllegalStateException.class,
                () -> TopicSaleTimeValidator.assertValid(topic, Collections.singletonList(ticket()), start, false));
        assertTrue(without.getMessage().contains("未开放承接"));
        assertDoesNotThrow(() -> TopicSaleTimeValidator.assertValid(topic,
                Collections.singletonList(ticket()), start, true));
    }

    @Test
    void topicHardSaleEndMustNotOutliveTopicFulfillmentEnd() {
        CmsTopic topic = sellableTopic();
        topic.setRecruitDeadline(at("2026-08-11T01:00:00Z"));
        topic.setSaleEndAt(at("2026-08-13T16:00:00Z"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> TopicSaleTimeValidator.assertValid(topic, Collections.singletonList(ticket()), at("2026-08-11T01:00:00Z")));
        assertTrue(failure.getMessage().contains("saleEndAt")
                        && failure.getMessage().contains("topicFulfillmentEndAt"),
                "售止越过履约末端必须点名相邻字段");
    }

    @Test
    void ticketEarlyDeadlineMustNotExceedTopicHardSaleEnd() {
        CmsTopic topic = sellableTopic();
        topic.setRecruitDeadline(at("2026-08-11T01:00:00Z"));
        OmsTicket ticket = ticket();
        ticket.setSignupDeadline(at("2026-08-12T16:00:00Z"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> TopicSaleTimeValidator.assertValid(topic, Collections.singletonList(ticket), at("2026-08-11T01:00:00Z")));
        assertTrue(failure.getMessage().contains("ticket.signupDeadline")
                        && failure.getMessage().contains("saleEndAt"),
                "票种提前售止越界必须点名相邻字段");
    }

    private static CmsTopic sellableTopic() {
        CmsTopic topic = new CmsTopic();
        topic.setProductType(ProductType.FREE_EXPLORE);
        topic.setStartDate(day("2026-08-11"));
        topic.setEndDate(day("2026-08-12"));
        topic.setSaleEndAt(at("2026-08-12T15:00:00Z"));
        return topic;
    }

    private static OmsTicket ticket() {
        OmsTicket ticket = new OmsTicket();
        ticket.setPrice(BigDecimal.ZERO);
        ticket.setTotalInventory(10L);
        ticket.setRemainingInventory(10L);
        ticket.setStartTime(at("2026-08-11T02:00:00Z"));
        ticket.setEndTime(at("2026-08-12T16:00:00Z"));
        return ticket;
    }

    private static Date day(String day) {
        return Date.from(LocalDate.parse(day).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());
    }

    private static Date at(String instant) {
        return Date.from(Instant.parse(instant));
    }
}
