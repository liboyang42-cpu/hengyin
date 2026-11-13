package com.chengyinhub.business.appgaps;

import com.chengyinhub.business.appgaps.mapper.SupportTicketReadMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SupportTicketReadsTest {
    @Test void requestShapesMirrorAppRoute() {
        SupportTicketReads r = new SupportTicketReads(mock(SupportTicketReadMapper.class));
        assertThrows(ServiceException.class, () -> r.list(9, Map.of("cursor", -1)));
        assertThrows(ServiceException.class, () -> r.list(9, Map.of("cursor", 0, "x", 1)));
        assertThrows(ServiceException.class, () -> r.detail(9, Map.of("ticket_no", "XX1", "cursor", 0)), "工单号必须 CS 前缀");
        assertThrows(ServiceException.class, () -> r.detail(9, Map.of("ticket_no", "CS1", "cursor", 3, "event_cursor", 0)), "时间线读取 cursor 必须为 0");
    }

    @Test void eventsHideRequestIdForNonMemberActors() {
        Map<String, Object> e = SupportTicketReads.event(Map.of("id", 4L, "event_type", "REPLY", "actor_type", "SUPPORT", "result_state", "APPLIED", "request_id", "agent-r"));
        assertFalse(e.containsKey("request_id"));
    }
}
