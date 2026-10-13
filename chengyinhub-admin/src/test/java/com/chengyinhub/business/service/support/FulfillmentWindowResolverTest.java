package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 合同二履约窗：有票只读票，无票自玩只读主题日粒度履约窗。 */
class FulfillmentWindowResolverTest {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    @Test
    void ticketBackedSkuUsesTicketWindowInsteadOfTopicDates() {
        CmsTopic topic = topic("2026-08-11", "2026-08-12");
        OmsTicket ticket = new OmsTicket();
        ticket.setStartTime(at("2026-08-11T03:00:00Z"));
        ticket.setEndTime(at("2026-08-11T05:00:00Z"));

        FulfillmentWindowResolver.Window window = FulfillmentWindowResolver.resolve(topic, ticket);

        assertEquals(ticket.getStartTime(), window.getStartInclusive());
        assertEquals(ticket.getEndTime(), window.getEndExclusive());
    }

    @Test
    void selfPlayWithoutTicketUsesShanghaiTopicDayWindow() {
        CmsTopic topic = topic("2026-08-11", "2026-08-12");

        FulfillmentWindowResolver.Window window = FulfillmentWindowResolver.resolve(topic, null);

        assertEquals(dayStart("2026-08-11"), window.getStartInclusive());
        assertEquals(dayStart("2026-08-13"), window.getEndExclusive());
    }

    @Test
    void missingTicketWindowFailsClosedWithNamedFields() {
        CmsTopic topic = topic("2026-08-11", "2026-08-12");
        OmsTicket ticket = new OmsTicket();
        ticket.setStartTime(at("2026-08-11T03:00:00Z"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> FulfillmentWindowResolver.resolve(topic, ticket));
        assertTrue(failure.getMessage().contains("ticket.startTime")
                        && failure.getMessage().contains("ticket.endTime"),
                "票种履约边界缺失必须点名相邻字段");
    }

    private static CmsTopic topic(String start, String end) {
        CmsTopic topic = new CmsTopic();
        topic.setStartDate(dayStart(start));
        topic.setEndDate(dayStart(end));
        return topic;
    }

    private static Date dayStart(String day) {
        return Date.from(LocalDate.parse(day).atStartOfDay(SHANGHAI).toInstant());
    }

    private static Date at(String instant) {
        return Date.from(Instant.parse(instant));
    }
}
