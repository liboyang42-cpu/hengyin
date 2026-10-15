package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsActivity;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.dto.ActivityTicketRequest;
import com.chengyinhub.business.domain.dto.CmsActivityPublishRequest;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.impl.CmsActivityServiceImpl;
import com.chengyinhub.business.service.impl.CmsTopicServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * P0: 小程序主题/活动发布直写票务 Mapper 时，也必须走与后台相同的票务不变量。
 */
class OmsTicketPublicCreationInvariantTest {

    @Test
    void topicCreationRejectsNegativeInventoryBeforeWritingTicket() {
        OmsTicketMapper ticketMapper = mock(OmsTicketMapper.class);
        CmsTopicServiceImpl service = new CmsTopicServiceImpl();
        org.springframework.test.util.ReflectionTestUtils.setField(service, "supplyReviewService", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.TopicSupplyReviewService.class,
                invocation -> "lockTopic".equals(invocation.getMethod().getName())
                        ? ((com.chengyinhub.business.mapper.CmsTopicMapper) org.springframework.test.util.ReflectionTestUtils.getField(service, "cmsTopicMapper")).selectCmsTopicByIdForUpdate(invocation.getArgument(0))
                        : org.mockito.Answers.RETURNS_DEFAULTS.answer(invocation)));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "settlementDebtLedger", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.SettlementDebtLedger.class));
        ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);

        ActivityTicketRequest ticket = validTicket();
        ticket.setTotalStock(-1);

        ServiceException ex = assertThrows(ServiceException.class, () -> ReflectionTestUtils.invokeMethod(
                service, "saveTickets", Collections.singletonList(ticket), 701L, 88L, "creator", 1, false));

        assertEquals(400, ex.getCode());
        verify(ticketMapper, never()).insertOmsTicket(any());
    }

    @Test
    void activityPublishRejectsReversedTicketWindowBeforeWritingTicket() {
        OmsTicketMapper ticketMapper = mock(OmsTicketMapper.class);
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        CmsActivityServiceImpl service = new CmsActivityServiceImpl();
        ReflectionTestUtils.setField(service, "memberIdentityService", mock(com.chengyinhub.business.service.impl.MemberIdentityService.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "settlementDebtLedger", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.SettlementDebtLedger.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "activitySettlementHoldMapper", org.mockito.Mockito.mock(com.chengyinhub.business.mapper.ActivitySettlementHoldMapper.class));
        ReflectionTestUtils.setField(service, "omsTicketMapper", ticketMapper);
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "auditTaskService", mock(IAuditTaskService.class));
        // 收费独立活动只允许平台账号发布(F-W2-01)；本用例只验证票窗口校验，发布者按平台账号构造。
        ReflectionTestUtils.setField(service, "platformMemberId", 88L);
        when(activityMapper.insertCmsActivity(any(CmsActivity.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, CmsActivity.class).setId(702L);
            return 1;
        });

        ActivityTicketRequest ticket = validTicket();
        ticket.setEndTime(ticket.getStartTime());
        CmsActivityPublishRequest request = new CmsActivityPublishRequest();
        request.setName("夜游活动");
        request.setStartDate(new Date(1_800_000_000_000L));
        request.setEndDate(new Date(1_800_086_400_000L));
        request.setTickets(Collections.singletonList(ticket));

        ServiceException ex = assertThrows(ServiceException.class,
                () -> service.publishActivity(request, 88L, "creator"));

        assertEquals(400, ex.getCode());
        verify(ticketMapper, never()).insertOmsTicket(any());
    }

    @Test
    void activityPublishLocksAndRejectsDissolvedClubBeforeInsert() {
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        ClubMapper clubMapper = mock(ClubMapper.class);
        CmsActivityServiceImpl service = new CmsActivityServiceImpl();
        ReflectionTestUtils.setField(service, "memberIdentityService", mock(com.chengyinhub.business.service.impl.MemberIdentityService.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "settlementDebtLedger", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.SettlementDebtLedger.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "activitySettlementHoldMapper", org.mockito.Mockito.mock(com.chengyinhub.business.mapper.ActivitySettlementHoldMapper.class));
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "clubMapper", clubMapper);
        CmsActivityPublishRequest request = requestForClub(73L);
        when(clubMapper.selectClubByIdForUpdate(73L)).thenReturn(null);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.publishActivity(request, 88L, "creator"));

        assertTrue(error.getMessage().contains("俱乐部已解散"),
                "该红必须指认到发布活动的解散互斥闸，实际:" + error.getMessage());
        verify(activityMapper, never()).insertCmsActivity(any());
    }

    @Test
    void activityPublishRejectsForgedClubOwnership() {
        CmsActivityMapper activityMapper = mock(CmsActivityMapper.class);
        ClubMapper clubMapper = mock(ClubMapper.class);
        CmsActivityServiceImpl service = new CmsActivityServiceImpl();
        ReflectionTestUtils.setField(service, "memberIdentityService", mock(com.chengyinhub.business.service.impl.MemberIdentityService.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "settlementDebtLedger", org.mockito.Mockito.mock(com.chengyinhub.business.service.support.SettlementDebtLedger.class));
        org.springframework.test.util.ReflectionTestUtils.setField(service, "activitySettlementHoldMapper", org.mockito.Mockito.mock(com.chengyinhub.business.mapper.ActivitySettlementHoldMapper.class));
        ReflectionTestUtils.setField(service, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(service, "clubMapper", clubMapper);
        Club club = new Club();
        club.setId(73L);
        club.setMemberId(99L);
        when(clubMapper.selectClubByIdForUpdate(73L)).thenReturn(club);

        IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                () -> service.publishActivity(requestForClub(73L), 88L, "forger"));

        assertEquals("只能向自己主理的俱乐部发布活动", error.getMessage());
        verify(activityMapper, never()).insertCmsActivity(any());
    }

    private CmsActivityPublishRequest requestForClub(Long clubId) {
        CmsActivityPublishRequest request = new CmsActivityPublishRequest();
        request.setClubId(clubId);
        request.setName("夜游活动");
        request.setStartDate(new Date(1_800_000_000_000L));
        request.setEndDate(new Date(1_800_086_400_000L));
        return request;
    }

    private ActivityTicketRequest validTicket() {
        return new ActivityTicketRequest("单日票", new BigDecimal("10"),
                new Date(1_800_000_000_000L), new Date(1_800_003_600_000L), 10, null);
    }
}
