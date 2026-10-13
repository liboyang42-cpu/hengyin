package com.chengyinhub.business.util;

import com.chengyinhub.business.domain.CmsTopic;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 自玩退款不可空读 ticket，退款边界复用主题履约窗起点。 */
class RefundFulfillmentWindowTest {

    @Test
    void selfPlayUsesTopicFulfillmentStartAndTreatsBoundaryAsClosed() {
        CmsTopic topic = new CmsTopic();
        topic.setStartDate(day("2026-08-11"));
        topic.setEndDate(day("2026-08-12"));
        Date start = day("2026-08-11");

        assertTrue(RefundPolicy.evaluateTopicFulfillmentWindow(2, 0, topic, null,
                new Date(start.getTime() - 1L)).refundable);
        assertFalse(RefundPolicy.evaluateTopicFulfillmentWindow(2, 0, topic, null, start).refundable);
    }

    private static Date day(String day) {
        return Date.from(LocalDate.parse(day).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant());
    }
}
