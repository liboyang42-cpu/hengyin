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
                assertEquals(Long.valueOf(9L), service.publishActivity(request, 88L, "test", true));
                verify(identity, never()).checkRegistered(any());
                verify(tickets).insertOmsTicket(any());
            } else {
                assertThrows(ServiceException.class, () -> service.publishActivity(request, 88L, "test", true));
                verify(activities, never()).insertCmsActivity(any());
                verify(tickets, never()).insertOmsTicket(any());
            }
        }
    }

    /** F-L4-01/F-W2-01:实名通过的非平台发布者发收费独立活动,发布即拒(下单必被二清闸拒);平台自办放行。 */
    @Test
    void standalonePaidActivityRequiresPlatformPublisher() {
        for (Long publisher : Arrays.asList(88L, 100000L)) {
            CmsActivityServiceImpl service = new CmsActivityServiceImpl();
            MemberIdentityService identity = mock(MemberIdentityService.class);
            when(identity.checkRegistered(any())).thenReturn(null);
            CmsActivityMapper activities = mock(CmsActivityMapper.class);
            OmsTicketMapper tickets = mock(OmsTicketMapper.class);
            ReflectionTestUtils.setField(service, "memberIdentityService", identity);
            ReflectionTestUtils.setField(service, "settlementDebtLedger", mock(SettlementDebtLedger.class));
            ReflectionTestUtils.setField(service, "cmsActivityMapper", activities);
            ReflectionTestUtils.setField(service, "omsTicketMapper", tickets);
            ReflectionTestUtils.setField(service, "auditTaskService", mock(IAuditTaskService.class));
            ReflectionTestUtils.setField(service, "platformMemberId", 100000L);
            when(activities.insertCmsActivity(any())).thenAnswer(call -> {
                call.getArgument(0, CmsActivity.class).setId(9L); return 1;
            });
            CmsActivityPublishRequest request = new CmsActivityPublishRequest();
            request.setName("收费独立活动");
            request.setStartDate(new Date(1800000000000L));
            request.setEndDate(new Date(1800086400000L));
            ActivityTicketRequest ticket = new ActivityTicketRequest();
            ticket.setName("测试票"); ticket.setPrice(new BigDecimal("0.01")); ticket.setTotalStock(10);
            ticket.setStartTime(request.getStartDate()); ticket.setEndTime(request.getEndDate());
            request.setTickets(Collections.singletonList(ticket));
            if (publisher == 88L) {
                ServiceException e = assertThrows(ServiceException.class,
                        () -> service.publishActivity(request, publisher, "test", true));
                assertTrue(e.getMessage().contains("票价只能是 0"), e.getMessage());
                verify(activities, never()).insertCmsActivity(any());
                verify(tickets, never()).insertOmsTicket(any());
            } else {
                assertEquals(Long.valueOf(9L), service.publishActivity(request, publisher, "test", true));
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
