package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.mapper.ClubLeadMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.service.ICmsActivityService;
import com.chengyinhub.business.service.ICmsRegistrationService;
import com.chengyinhub.business.service.support.ClubGatherArrivalPolicy;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class ClubGatherArrivalControllerTest {
    private ApiClubLeadController controller;
    private CmsActivityMapper activityMapper;
    private ClubLeadMapper leadMapper;
    private ICmsRegistrationService registrations;
    private CmsActivity activity;
    private ClubLeadState state;

    @BeforeEach void setUp() {
        controller = spy(new ApiClubLeadController()); doReturn(7L).when(controller).getAppUserId();
        activityMapper = mock(CmsActivityMapper.class); leadMapper = mock(ClubLeadMapper.class);
        registrations = mock(ICmsRegistrationService.class);
        ICmsActivityService activities = mock(ICmsActivityService.class);
        com.chengyinhub.business.access.club.ClubEventLeadResolver resolver = mock(com.chengyinhub.business.access.club.ClubEventLeadResolver.class);
        ReflectionTestUtils.setField(controller, "cmsActivityMapper", activityMapper);
        ReflectionTestUtils.setField(controller, "clubLeadMapper", leadMapper);
        ReflectionTestUtils.setField(controller, "cmsActivityService", activities);
        ReflectionTestUtils.setField(controller, "cmsRegistrationService", registrations);
        ReflectionTestUtils.setField(controller, "clubEventLeadResolver", resolver);
        ReflectionTestUtils.setField(controller, "clubGatherArrivalPolicy", new ClubGatherArrivalPolicy());
        activity = new CmsActivity(); activity.setId(100L); activity.setClubId(5L); activity.setDelFlag(0);
        activity.setStartDate(new Date(System.currentTimeMillis() + 1800000L)); activity.setEndDate(new Date(System.currentTimeMillis() + 7200000L));
        activity.setLatitude("31.23"); activity.setLongitude("121.47");
        when(activityMapper.selectCmsActivityByIdForUpdate(100L)).thenReturn(activity);
        when(activities.selectCmsActivityById(100L)).thenReturn(activity);
        when(resolver.resolveLeaderMemberId(activity)).thenReturn(9L);
        state = new ClubLeadState(); state.setId(1L); state.setActivityId(100L); state.setLeaderMemberId(9L); state.setStatus(1);
        when(leadMapper.selectByActivity(100L)).thenReturn(state); when(leadMapper.insertCheckin(any())).thenReturn(1);
        CmsRegistration registration = new CmsRegistration(); registration.setOwnerType(2L); registration.setOwnerId(100L);
        registration.setMemberId(7L); registration.setPaymentStatus(2); registration.setRegistrationStatus(2);
        when(registrations.selectCmsRegistrationList(any())).thenReturn(Collections.singletonList(registration));
    }

    @Test void realCurrentScopeAndStoredMeetingPointProduceOnlyAnAttendanceFact() {
        assertEquals(200, controller.arrive("100", 31.23D, 121.47D).get("code"));
        ArgumentCaptor<CmsRegistration> probe = ArgumentCaptor.forClass(CmsRegistration.class);
        verify(registrations).selectCmsRegistrationList(probe.capture());
        assertEquals(2L, probe.getValue().getOwnerType().longValue()); assertEquals(100L, probe.getValue().getOwnerId().longValue());
        assertEquals(7L, probe.getValue().getMemberId().longValue()); assertEquals(2, probe.getValue().getPaymentStatus().intValue()); assertEquals(2, probe.getValue().getRegistrationStatus().intValue());
        ArgumentCaptor<ClubLeadCheckin> saved = ArgumentCaptor.forClass(ClubLeadCheckin.class);
        verify(leadMapper).insertCheckin(saved.capture()); assertEquals(7L, saved.getValue().getMemberId().longValue());
        assertEquals(100L, saved.getValue().getActivityId().longValue()); assertNotNull(saved.getValue().getArrivedAt());
        org.mockito.InOrder order = inOrder(activityMapper, leadMapper);
        order.verify(activityMapper).selectCmsActivityByIdForUpdate(100L); order.verify(leadMapper).selectByActivity(100L); order.verify(leadMapper).insertCheckin(any());
    }

    @Test void tooEarlyMissingPointAndBadReportedCoordinateWriteNeitherStateNorAttendance() {
        activity.setStartDate(new Date(System.currentTimeMillis() + 7200000L));
        assertNotEquals(200, controller.arrive("100", 31.23D, 121.47D).get("code"));
        activity.setStartDate(new Date(System.currentTimeMillis() + 1800000L)); activity.setLatitude(null);
        assertNotEquals(200, controller.arrive("100", 31.23D, 121.47D).get("code"));
        activity.setLatitude("31.23");
        assertNotEquals(200, controller.arrive("100", Double.NaN, 121.47D).get("code"));
        assertNotEquals(200, controller.arrive("100", 31.23D, null).get("code"));
        assertNotEquals(200, controller.arrive("100", 32D, 121.47D).get("code"));
        verifyNoInteractions(leadMapper, registrations);
    }

    @Test void startedRunStillAllowsArrivalButSettledRunDoesNot() {
        state.setStatus(2); assertEquals(200, controller.arrive("100", 31.23D, 121.47D).get("code"));
        clearInvocations(leadMapper); state.setStatus(3);
        assertNotEquals(200, controller.arrive("100", 31.23D, 121.47D).get("code")); verify(leadMapper, never()).insertCheckin(any());
        state.setStatus(null); assertNotEquals(200, controller.arrive("100", 31.23D, 121.47D).get("code"));
        state.setStatus(0); assertNotEquals(200, controller.arrive("100", 31.23D, 121.47D).get("code"));
        verify(leadMapper, never()).insertCheckin(any());
    }

    @Test void cancellationAndActualEndAreRejectedBeforeAttendance() {
        activity.setCancelTime(new Date()); assertNotEquals(200, controller.arrive("100", 31.23D, 121.47D).get("code"));
        activity.setCancelTime(null); activity.setStartDate(new Date(System.currentTimeMillis() - 7200000L)); activity.setEndDate(new Date(System.currentTimeMillis() - 1));
        assertNotEquals(200, controller.arrive("100", 31.23D, 121.47D).get("code")); verifyNoInteractions(leadMapper);
    }

    @Test void foreignRegistrationAndGuestCannotWriteCheckIn() {
        when(registrations.selectCmsRegistrationList(any())).thenReturn(Collections.emptyList());
        assertNotEquals(200, controller.arrive("100", 31.23D, 121.47D).get("code")); verify(leadMapper, never()).insertCheckin(any());
        clearInvocations(activityMapper); doReturn(0L).when(controller).getAppUserId();
        assertNotEquals(200, controller.arrive("100", 31.23D, 121.47D).get("code")); verifyNoInteractions(activityMapper);
    }

    @Test void leaderRemainsAllowedAndDuplicateArrivalRemainsIdempotent() {
        doReturn(9L).when(controller).getAppUserId(); when(leadMapper.insertCheckin(any())).thenThrow(new org.springframework.dao.DuplicateKeyException("uk_arrive"));
        assertEquals(200, controller.arrive("100", 31.23D, 121.47D).get("code")); verifyNoInteractions(registrations);
    }

    @Test void zeroInsertedRowsCannotBeReportedAsSuccessfulAttendance() {
        when(leadMapper.insertCheckin(any())).thenReturn(0);
        assertNotEquals(200, controller.arrive("100", 31.23D, 121.47D).get("code"));
    }

    @Test void actualEndpointIsTransactionalAndUsesOnlyActivityAndReportedCoordinates() throws Exception {
        java.lang.reflect.Method method = ApiClubLeadController.class.getMethod("arrive", String.class, Double.class, Double.class);
        assertNotNull(method.getAnnotation(org.springframework.transaction.annotation.Transactional.class));
        assertArrayEquals(new String[]{"/arrive"}, method.getAnnotation(org.springframework.web.bind.annotation.PostMapping.class).value());
    }
}
