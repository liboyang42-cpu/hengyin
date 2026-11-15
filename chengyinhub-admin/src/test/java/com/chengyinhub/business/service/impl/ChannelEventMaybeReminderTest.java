package com.chengyinhub.business.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.channelevent.ChannelEventService;
import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.OmsTicket;
import com.chengyinhub.business.domain.dto.BeforeStartNotifyResult.Delivery;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.INotifyService;
import com.chengyinhub.business.service.IPushFanoutService;
import com.chengyinhub.common.core.redis.RedisCache;

/**
 * C3: the existing before-start job reminds enrolled members (push + WeChat notice, unchanged) and now also pushes
 * to members who answered "maybe" on a channel event card. "Maybe" gets the app push only, never the WeChat notice.
 */
class ChannelEventMaybeReminderTest
{
    @Test
    void maybeMembersGetTheAppPushAndEnrolledMembersKeepBoth()
    {
        OmsTicket t = new OmsTicket();
        t.setId(7L);
        t.setName("周六夜跑");
        t.setOwnerType(2L);
        t.setOwnerId(900L);
        t.setStartTime(new Date(System.currentTimeMillis() + 3600_000L));

        OmsTicketMapper tickets = mock(OmsTicketMapper.class);
        when(tickets.selectTeamFormedStartingSoon(any(Date.class), any(Date.class))).thenReturn(List.of(t));
        CmsRegistrationMapper regs = mock(CmsRegistrationMapper.class);
        CmsRegistration enrolled = new CmsRegistration();
        enrolled.setMemberId(100L);
        when(regs.selectPaidByTicket(7L)).thenReturn(List.of(enrolled));
        RedisCache cache = mock(RedisCache.class);
        INotifyService notices = mock(INotifyService.class);
        when(notices.notifyBeforeStart(anyLong(), anyLong(), anyString(), any(Date.class))).thenReturn(new Delivery(1L, "ACCEPTED", true));
        IPushFanoutService push = mock(IPushFanoutService.class);
        ChannelEventService events = mock(ChannelEventService.class);
        when(events.maybeMembersForReminder(7L)).thenReturn(List.of(200L, 201L));

        TeamGroupServiceImpl service = new TeamGroupServiceImpl();
        ReflectionTestUtils.setField(service, "omsTicketMapper", tickets);
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", regs);
        ReflectionTestUtils.setField(service, "redisCache", cache);
        ReflectionTestUtils.setField(service, "notifyService", notices);
        ReflectionTestUtils.setField(service, "pushFanout", push);
        ReflectionTestUtils.setField(service, "channelEvents", events);

        assertEquals(1, service.notifyBeforeStartBatch().getDelivered());

        verify(push).eventReminder(eq(7L), eq(100L), eq("周六夜跑"), any(Date.class), eq(2L), eq(900L));
        verify(push).eventReminder(eq(7L), eq(200L), eq("周六夜跑"), any(Date.class), eq(2L), eq(900L));
        verify(push).eventReminder(eq(7L), eq(201L), eq("周六夜跑"), any(Date.class), eq(2L), eq(900L));
        verify(notices, never()).notifyBeforeStart(eq(7L), eq(200L), anyString(), any(Date.class));
    }

    @Test
    void aBrokenEventLookupNeverStopsTheEnrolledReminders()
    {
        OmsTicket t = new OmsTicket();
        t.setId(8L);
        t.setName("x");
        t.setStartTime(new Date(System.currentTimeMillis() + 3600_000L));
        OmsTicketMapper tickets = mock(OmsTicketMapper.class);
        when(tickets.selectTeamFormedStartingSoon(any(Date.class), any(Date.class))).thenReturn(List.of(t));
        CmsRegistrationMapper regs = mock(CmsRegistrationMapper.class);
        when(regs.selectPaidByTicket(8L)).thenReturn(Collections.emptyList());
        IPushFanoutService push = mock(IPushFanoutService.class);
        ChannelEventService events = mock(ChannelEventService.class);
        when(events.maybeMembersForReminder(8L)).thenThrow(new IllegalStateException("table missing"));
        TeamGroupServiceImpl service = new TeamGroupServiceImpl();
        ReflectionTestUtils.setField(service, "omsTicketMapper", tickets);
        ReflectionTestUtils.setField(service, "cmsRegistrationMapper", regs);
        ReflectionTestUtils.setField(service, "redisCache", mock(RedisCache.class));
        ReflectionTestUtils.setField(service, "notifyService", mock(INotifyService.class));
        ReflectionTestUtils.setField(service, "pushFanout", push);
        ReflectionTestUtils.setField(service, "channelEvents", events);
        assertEquals(0, service.notifyBeforeStartBatch().getScanned());
    }
}
