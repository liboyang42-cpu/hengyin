package com.chengyinhub.business.service.impl;

import java.util.Collections;
import java.util.Date;

import com.chengyinhub.business.domain.EventSignup;
import com.chengyinhub.business.domain.OfficialEvent;
import com.chengyinhub.business.mapper.EventSignupMapper;
import com.chengyinhub.business.mapper.MemberCollectibleMapper;
import com.chengyinhub.business.mapper.OfficialEventMapper;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.IOfficialEventV2Service;
import com.chengyinhub.business.service.ISmsCouponHistoryService;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class OfficialEventLifecycleTest {

    @Test
    void runStatusFlow_V1活动结束与结算同刻时同轮先结算再结束() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        EventSignupMapper signupMapper = mock(EventSignupMapper.class);
        IOfficialEventV2Service v2Service = mock(IOfficialEventV2Service.class);
        OfficialEventServiceImpl service = service(eventMapper, signupMapper, v2Service);

        Date boundary = new Date(System.currentTimeMillis() - 60_000L);
        OfficialEvent event = event(OfficialEvent.ST_LIVE, boundary, boundary);
        EventSignup signup = signup();
        when(eventMapper.selectActiveForFlow()).thenReturn(Collections.singletonList(event));
        when(eventMapper.selectOfficialEventById(1L)).thenReturn(event);
        when(eventMapper.updateOfficialEvent(org.mockito.ArgumentMatchers.any(OfficialEvent.class)))
                .thenAnswer(invocation -> {
                    OfficialEvent update = invocation.getArgument(0);
                    event.setStatus(update.getStatus());
                    return 1;
                });
        when(signupMapper.selectByEvent(1L)).thenReturn(Collections.singletonList(signup));
        when(signupMapper.claimReward(101L, EventSignup.ST_REWARDED)).thenReturn(1);

        service.runStatusFlow();

        InOrder lifecycle = inOrder(eventMapper, signupMapper);
        lifecycle.verify(eventMapper).updateOfficialEvent(argThat(update ->
                Integer.valueOf(OfficialEvent.ST_SETTLING).equals(update.getStatus())));
        lifecycle.verify(eventMapper).selectOfficialEventById(1L);
        lifecycle.verify(signupMapper).selectByEvent(1L);
        lifecycle.verify(signupMapper).claimReward(101L, EventSignup.ST_REWARDED);
        lifecycle.verify(eventMapper).updateOfficialEvent(argThat(update ->
                Integer.valueOf(OfficialEvent.ST_ENDED).equals(update.getStatus())));
        assertEquals(OfficialEvent.ST_ENDED, event.getStatus());
        verify(signupMapper, times(1)).claimReward(101L, EventSignup.ST_REWARDED);
        verify(eventMapper, times(2)).updateOfficialEvent(any(OfficialEvent.class));
        verify(v2Service).runLifecycle();
    }

    @Test
    void runStatusFlow_V1常规结算窗口保持SETTLING并执行结算() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        EventSignupMapper signupMapper = mock(EventSignupMapper.class);
        IOfficialEventV2Service v2Service = mock(IOfficialEventV2Service.class);
        IGrowthService growthService = mock(IGrowthService.class);
        OfficialEventServiceImpl service = service(eventMapper, signupMapper, v2Service,
                mock(ISmsCouponHistoryService.class), growthService);

        OfficialEvent event = event(OfficialEvent.ST_LIVE,
                new Date(System.currentTimeMillis() - 60_000L),
                new Date(System.currentTimeMillis() + 60_000L));
        event.setRewardJson("{\"settleXp\":50}");
        EventSignup signup = signup();
        signup.setProgress(1);
        when(eventMapper.selectActiveForFlow()).thenReturn(Collections.singletonList(event));
        when(eventMapper.selectOfficialEventById(1L)).thenReturn(event);
        when(eventMapper.updateOfficialEvent(any(OfficialEvent.class))).thenAnswer(invocation -> {
            OfficialEvent update = invocation.getArgument(0);
            event.setStatus(update.getStatus());
            return 1;
        });
        when(signupMapper.selectByEvent(1L)).thenReturn(Collections.singletonList(signup));
        when(signupMapper.claimReward(101L, EventSignup.ST_REWARDED)).thenReturn(1, 0);

        service.runStatusFlow();
        service.runStatusFlow();

        assertEquals(OfficialEvent.ST_SETTLING, event.getStatus());
        verify(eventMapper, times(1)).updateOfficialEvent(argThat(update ->
                Integer.valueOf(OfficialEvent.ST_SETTLING).equals(update.getStatus())));
        verify(signupMapper, times(2)).claimReward(101L, EventSignup.ST_REWARDED);
        verify(growthService, times(1)).addExp(501L, 50, "官方活动结算奖 event=1");
        verify(v2Service, times(2)).runLifecycle();
    }

    @Test
    void runStatusFlow_V1结算发奖失败时保留SETTLING供下轮重试() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        EventSignupMapper signupMapper = mock(EventSignupMapper.class);
        IOfficialEventV2Service v2Service = mock(IOfficialEventV2Service.class);
        ISmsCouponHistoryService couponService = mock(ISmsCouponHistoryService.class);
        OfficialEventServiceImpl service = service(eventMapper, signupMapper, v2Service,
                couponService, mock(IGrowthService.class));

        Date boundary = new Date(System.currentTimeMillis() - 60_000L);
        OfficialEvent event = event(OfficialEvent.ST_LIVE, boundary, boundary);
        event.setRewardJson("{\"settleCouponId\":77}");
        EventSignup signup = signup();
        signup.setProgress(1);
        when(eventMapper.selectActiveForFlow()).thenReturn(Collections.singletonList(event));
        when(eventMapper.selectOfficialEventById(1L)).thenReturn(event);
        when(eventMapper.updateOfficialEvent(any(OfficialEvent.class))).thenAnswer(invocation -> {
            OfficialEvent update = invocation.getArgument(0);
            event.setStatus(update.getStatus());
            return 1;
        });
        when(signupMapper.selectByEvent(1L)).thenReturn(Collections.singletonList(signup));
        when(signupMapper.claimReward(101L, EventSignup.ST_REWARDED)).thenReturn(1);
        when(couponService.grantCouponById(501L, 77L)).thenReturn(false);

        service.runStatusFlow();

        assertEquals(OfficialEvent.ST_SETTLING, event.getStatus());
        verify(eventMapper, times(1)).updateOfficialEvent(argThat(update ->
                Integer.valueOf(OfficialEvent.ST_SETTLING).equals(update.getStatus())));
        verify(signupMapper, times(1)).claimReward(101L, EventSignup.ST_REWARDED);
        verify(v2Service).runLifecycle();
    }

    @Test
    void runStatusFlow_V2活动保持委托现有生命周期且V1路径不写入() {
        OfficialEventMapper eventMapper = mock(OfficialEventMapper.class);
        EventSignupMapper signupMapper = mock(EventSignupMapper.class);
        IOfficialEventV2Service v2Service = mock(IOfficialEventV2Service.class);
        OfficialEventServiceImpl service = service(eventMapper, signupMapper, v2Service);

        Date boundary = new Date(System.currentTimeMillis() - 60_000L);
        OfficialEvent event = event(OfficialEvent.ST_LIVE, boundary, boundary);
        event.setContractVersion(2);
        when(eventMapper.selectActiveForFlow()).thenReturn(Collections.singletonList(event));

        service.runStatusFlow();

        assertEquals(OfficialEvent.ST_LIVE, event.getStatus());
        verify(eventMapper, never()).updateOfficialEvent(any(OfficialEvent.class));
        verify(signupMapper, never()).selectByEvent(any(Long.class));
        verify(v2Service).runLifecycle();
    }

    private OfficialEventServiceImpl service(OfficialEventMapper eventMapper,
                                             EventSignupMapper signupMapper,
                                             IOfficialEventV2Service v2Service) {
        return service(eventMapper, signupMapper, v2Service,
                mock(ISmsCouponHistoryService.class), mock(IGrowthService.class));
    }

    private OfficialEventServiceImpl service(OfficialEventMapper eventMapper,
                                             EventSignupMapper signupMapper,
                                             IOfficialEventV2Service v2Service,
                                             ISmsCouponHistoryService couponService,
                                             IGrowthService growthService) {
        OfficialSettlementHelper settlementHelper = new OfficialSettlementHelper();
        ReflectionTestUtils.setField(settlementHelper, "signupMapper", signupMapper);
        ReflectionTestUtils.setField(settlementHelper, "collectibleMapper", mock(MemberCollectibleMapper.class));
        ReflectionTestUtils.setField(settlementHelper, "couponHistoryService", couponService);
        ReflectionTestUtils.setField(settlementHelper, "growthService", growthService);
        ReflectionTestUtils.setField(settlementHelper, "alarmService", mock(AlarmService.class));

        OfficialEventServiceImpl service = new OfficialEventServiceImpl();
        ReflectionTestUtils.setField(service, "eventMapper", eventMapper);
        ReflectionTestUtils.setField(service, "signupMapper", signupMapper);
        ReflectionTestUtils.setField(service, "settlementHelper", settlementHelper);
        ReflectionTestUtils.setField(service, "officialEventV2Service", v2Service);
        return service;
    }

    private OfficialEvent event(int status, Date activityEnd, Date settleTime) {
        OfficialEvent event = new OfficialEvent();
        event.setId(1L);
        event.setStatus(status);
        event.setContractVersion(1);
        event.setActivityEnd(activityEnd);
        event.setSettleTime(settleTime);
        return event;
    }

    private EventSignup signup() {
        EventSignup signup = new EventSignup();
        signup.setId(101L);
        signup.setEventId(1L);
        signup.setMemberId(501L);
        signup.setProgress(0);
        signup.setRewardClaimed(0);
        signup.setStatus(EventSignup.ST_DOING);
        return signup;
    }
}
