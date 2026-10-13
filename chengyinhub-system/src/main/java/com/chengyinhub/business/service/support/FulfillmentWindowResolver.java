package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.OmsTicket;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;

/** 合同二履约真源解析：票种有票读票，自玩无票读主题日粒度窗。 */
public final class FulfillmentWindowResolver {

    private static final ZoneId SHANGHAI = ZoneId.of("Asia/Shanghai");

    private FulfillmentWindowResolver() {
    }

    public static Window resolve(CmsTopic topic, OmsTicket ticket) {
        if (ticket != null) {
            if (ticket.getStartTime() == null || ticket.getEndTime() == null) {
                throw new IllegalStateException("ticket.startTime 与 ticket.endTime 必须同时存在");
            }
            if (!ticket.getStartTime().before(ticket.getEndTime())) {
                throw new IllegalStateException("ticket.startTime 必须早于 ticket.endTime");
            }
            return new Window(ticket.getStartTime(), ticket.getEndTime());
        }
        if (topic == null || topic.getStartDate() == null || topic.getEndDate() == null) {
            throw new IllegalStateException("topic.startDate 与 topic.endDate 必须同时存在");
        }
        LocalDate startDate = topic.getStartDate().toInstant().atZone(SHANGHAI).toLocalDate();
        LocalDate endDate = topic.getEndDate().toInstant().atZone(SHANGHAI).toLocalDate();
        if (endDate.isBefore(startDate)) {
            throw new IllegalStateException("topic.startDate 必须早于或等于 topic.endDate");
        }
        return new Window(Date.from(startDate.atStartOfDay(SHANGHAI).toInstant()),
                Date.from(endDate.plusDays(1).atStartOfDay(SHANGHAI).toInstant()));
    }

    public static final class Window {
        private final Date startInclusive;
        private final Date endExclusive;

        private Window(Date startInclusive, Date endExclusive) {
            this.startInclusive = startInclusive;
            this.endExclusive = endExclusive;
        }

        public Date getStartInclusive() {
            return startInclusive;
        }

        public Date getEndExclusive() {
            return endExclusive;
        }
    }
}
