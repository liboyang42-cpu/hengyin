package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.*;
import com.chengyinhub.business.mapper.*;
import com.chengyinhub.business.service.impl.*;
import com.chengyinhub.business.service.support.SettlementDebtLedger;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

class FreeClubPublisherIdentityTest {
    @Test
    void activityPublishAllowsZeroButRejectsPaidBeforeAnyWrite() {
        for (BigDecimal price : Arrays.asList(BigDecimal.ZERO, BigDecimal.ONE)) {
            CmsActivityServiceImpl service = new CmsActivityServiceImpl();
            MemberIdentityService identity = mock(MemberIdentityService.class);
            when(identity.checkRegistered(88L)).thenReturn("请先登记实名信息");
            CmsActivityMapper activities = mock(CmsActivityMapper.class);
            OmsTicketMapper tickets = mock(OmsTicketMapper.class);
            ReflectionTestUtils.setField(service, "memberIdentityService", identity);
            ReflectionTestUtils.setField(service, "settlementDebtLedger", mock(SettlementDebtLedger.class));
            ReflectionTestUtils.setField(service, "cmsActivityMapper", activities);
            ReflectionTestUtils.setField(service, "omsTicketMapper", tickets);
            ReflectionTestUtils.setField(service, "auditTaskService", mock(IAuditTaskService.class));
            when(activities.insertCmsActivity(any())).thenAnswer(call -> {
                call.getArgument(0, CmsActivity.class).setId(9L); return 1;
            });
            CmsActivityPublishRequest request = new CmsActivityPublishRequest();
            request.setName("免费测试");
            request.setStartDate(new Date(1800000000000L));
            request.setEndDate(new Date(1800086400000L));
            ActivityTicketRequest ticket = new ActivityTicketRequest();
            ticket.setName("测试票"); ticket.setPrice(price); ticket.setTotalStock(10);
            ticket.setStartTime(request.getStartDate()); ticket.setEndTime(request.getEndDate());
            request.setTickets(Collections.singletonList(ticket));
            if (price.signum() == 0) {
                assertEquals(Long.valueOf(9L), service.publishActivity(request, 88L, "test"));
                verify(identity, never()).checkRegistered(any());
                verify(tickets).insertOmsTicket(any());
            } else {
                assertThrows(ServiceException.class, () -> service.publishActivity(request, 88L, "test"));
                verify(activities, never()).insertCmsActivity(any());
                verify(tickets, never()).insertOmsTicket(any());
            }
        }
    }

    @Test
    void clubSessionCopiesFreeTicketsButRequiresIdentityForAnyPaidTicket() {
        for (BigDecimal price : Arrays.asList(BigDecimal.ZERO, BigDecimal.ONE)) {
            OmsTicketServiceImpl service = new OmsTicketServiceImpl();
            OmsTicketMapper tickets = mock(OmsTicketMapper.class);
            MemberIdentityService identity = mock(MemberIdentityService.class);
            when(identity.checkRegistered(88L)).thenReturn("请先登记实名信息");
            ReflectionTestUtils.setField(service, "memberIdentityService", identity);
            ReflectionTestUtils.setField(service, "omsTicketMapper", tickets);
            ReflectionTestUtils.setField(service, "cmsActivityMapper", mock(CmsActivityMapper.class));
            OmsTicket free = new OmsTicket(); free.setId(1L); free.setPrice(BigDecimal.ZERO);
            OmsTicket source = new OmsTicket(); source.setId(2L); source.setPrice(price);
            when(tickets.selectOmsTicketList(any())).thenReturn(Collections.emptyList(), Arrays.asList(free, source));
            if (price.signum() == 0) {
                assertEquals(2, service.copyTopicTicketsToActivity(10L, 9L, 88L));
                verify(identity, never()).checkRegistered(any());
            } else {
                assertThrows(ServiceException.class, () -> service.copyTopicTicketsToActivity(10L, 9L, 88L));
                verify(tickets, never()).insertOmsTicket(any());
            }
        }
    }
}
