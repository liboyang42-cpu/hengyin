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

/**
 * 合同二 §2.3：非法时间必须指认相邻的两个时间，不得返回笼统“时间错误”。
 * 第十轮 f2 第 4 项起用中文业务名点名(审核台和主办方直接看到原文),不再出现字段名。
 */
class TopicSaleTimeValidatorTest {

    @Test
    void projectDatesRejectEqualOrReversedRecruitmentAndSaleDeadlines() {
        CmsTopic topic = sellableTopic();
        for (long offset : new long[] {0, 1}) {
            topic.setRecruitDeadline(new Date(topic.getSaleEndAt().getTime() + offset));
            com.chengyinhub.common.exception.ServiceException failure = assertThrows(
                    com.chengyinhub.common.exception.ServiceException.class,
                    () -> TopicSaleTimeValidator.assertRecruitmentSaleWindow(topic));
            assertTrue(failure.getMessage().contains("招商截止日期")
                    && failure.getMessage().contains("售票截止时间"));
            assertNoFieldNames(failure.getMessage());
        }
    }

    @Test
    void projectDatesAllowFutureRecruitmentWithoutPricingOrAStartDateRestriction() {
        CmsTopic topic = new CmsTopic();
        topic.setStartDate(at("2099-01-01T00:00:00Z"));
        topic.setRecruitDeadline(at("2099-01-02T00:00:00Z"));
        topic.setSaleEndAt(at("2099-01-03T00:00:00Z"));
        assertDoesNotThrow(() -> TopicSaleTimeValidator.assertRecruitmentSaleWindow(topic));
        topic.setSaleEndAt(null);
        assertDoesNotThrow(() -> TopicSaleTimeValidator.assertRecruitmentSaleWindow(topic));
        topic.setSaleEndAt(at("2099-01-03T00:00:00Z"));
        topic.setRecruitDeadline(null);
        assertDoesNotThrow(() -> TopicSaleTimeValidator.assertRecruitmentSaleWindow(topic));
    }

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
        assertTrue(failure.getMessage().contains("招商截止日期"), "开放承接必须点名招商截止日期");
        assertNoFieldNames(failure.getMessage());
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
        OmsTicket guided = ticket();
        guided.setStartTime(at("2026-08-12T15:00:00Z"));
        guided.setEndTime(at("2026-08-12T16:00:00Z"));

        IllegalStateException without = assertThrows(IllegalStateException.class,
                () -> TopicSaleTimeValidator.assertValid(topic, Collections.singletonList(guided), start, () -> false));
        assertTrue(without.getMessage().contains("未开放承接"));
        assertDoesNotThrow(() -> TopicSaleTimeValidator.assertValid(topic,
                Collections.singletonList(guided), start, () -> true));
    }

    @Test
    void topicHardSaleEndMustNotOutliveTopicFulfillmentEnd() {
        CmsTopic topic = sellableTopic();
        topic.setRecruitDeadline(at("2026-08-11T01:00:00Z"));
        topic.setSaleEndAt(at("2026-08-13T16:00:00Z"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> TopicSaleTimeValidator.assertValid(topic, Collections.singletonList(ticket()), at("2026-08-11T01:00:00Z")));
        assertTrue(failure.getMessage().contains("售票截止时间")
                        && failure.getMessage().contains("主题结束时间"),
                "售止越过履约末端必须点名相邻的两个时间");
        assertNoFieldNames(failure.getMessage());
    }

    @Test
    void ticketEarlyDeadlineMustNotExceedTopicHardSaleEnd() {
        CmsTopic topic = sellableTopic();
        topic.setRecruitDeadline(at("2026-08-11T01:00:00Z"));
        OmsTicket ticket = ticket();
        ticket.setSignupDeadline(at("2026-08-12T16:00:00Z"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> TopicSaleTimeValidator.assertValid(topic, Collections.singletonList(ticket), at("2026-08-11T01:00:00Z")));
        assertTrue(failure.getMessage().contains("报名截止时间")
                        && failure.getMessage().contains("主题售票截止时间"),
                "票种提前售止越界必须点名相邻的两个时间");
        assertNoFieldNames(failure.getMessage());
    }

    @Test
    void guidedTicketSaleEndAfterStart_namesTheTicketInChinese() {
        CmsTopic topic = sellableTopic();
        topic.setProductType(ProductType.ORIENTEERING);
        topic.setMerchantStatus(0);
        topic.setOpenClubPool(0);
        OmsTicket guided = ticket();
        guided.setName("领队场");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> TopicSaleTimeValidator.assertValid(topic, Collections.singletonList(guided), at("2026-08-11T01:00:00Z")));
        assertTrue(failure.getMessage().contains("票种「领队场」"), failure.getMessage());
        assertNoFieldNames(failure.getMessage());
    }

    /** 第十轮 f2 第 4 项:俱乐部主题按场次卖,售票截止跟主题结束走;同样的票放在非俱乐部主题上仍按单次成行拒。 */
    @Test
    void clubThemeSoldBySessions_mayKeepSellingThroughItsWindow() {
        CmsTopic topic = sellableTopic();
        topic.setProductType(ProductType.ORIENTEERING);
        topic.setMerchantStatus(0);
        topic.setOpenClubPool(0);
        topic.setSaleEndAt(at("2026-08-12T15:59:59Z"));
        OmsTicket template = ticket();
        template.setName("领队场");
        template.setStartTime(at("2026-08-10T16:00:00Z"));
        template.setEndTime(at("2026-08-12T15:59:59Z"));
        topic.setClubId(990004L);
        assertDoesNotThrow(() -> TopicSaleTimeValidator.assertValid(topic,
                Collections.singletonList(template), at("2026-08-11T01:00:00Z")));
        topic.setClubId(null);
        assertThrows(IllegalStateException.class, () -> TopicSaleTimeValidator.assertValid(topic,
                Collections.singletonList(template), at("2026-08-11T01:00:00Z")));
    }

    private static void assertNoFieldNames(String message) {
        assertTrue(!message.matches(".*[A-Za-z]{3,}.*"), "审核台看到的拒绝原因不得含字段名: " + message);
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
