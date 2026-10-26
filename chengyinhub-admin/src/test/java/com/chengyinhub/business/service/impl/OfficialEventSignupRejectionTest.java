package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.EventSignup;
import com.chengyinhub.business.domain.OfficialEvent;
import com.chengyinhub.business.domain.OfficialEventParticipation;
import com.chengyinhub.business.mapper.EventSignupMapper;
import com.chengyinhub.business.mapper.OfficialEventMapper;
import com.chengyinhub.business.mapper.OfficialEventV2Mapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Service rejection effects only; does not stand in for MySQL concurrency verification. */
class OfficialEventSignupRejectionTest {
    private OfficialEventV2ServiceImpl service;
    private OfficialEventV2Mapper participation;
    private EventSignupMapper legacy;
    private OfficialEvent event;

    @BeforeEach
    void setUp() {
        service = new OfficialEventV2ServiceImpl();
        OfficialEventMapper events = mock(OfficialEventMapper.class);
        participation = mock(OfficialEventV2Mapper.class);
        legacy = mock(EventSignupMapper.class);
        ReflectionTestUtils.setField(service, "eventMapper", events);
        ReflectionTestUtils.setField(service, "v2Mapper", participation);
        ReflectionTestUtils.setField(service, "signupMapper", legacy);
        event = new OfficialEvent();
        event.setId(7L);
        event.setContractVersion(2);
        event.setStatus(OfficialEvent.ST_SIGNUP);
        event.setSignupLimit(1);
        when(events.selectOfficialEventByIdForUpdate(7L)).thenReturn(event);
    }

    @Test
    void invalidParticipationCannotCreateLegacySignupOnRejectedRetry() {
        for (String state : new String[] { "LEFT", "REFUNDED", "BANNED" }) {
            OfficialEventParticipation row = new OfficialEventParticipation();
            row.setState(state);
            when(participation.selectParticipation(7L, 101L)).thenReturn(row);
            assertEquals("当前参与资格已失效", service.signup(7L, 101L));
        }
        verifyNoInteractions(legacy);
        verify(participation, never()).insertParticipationIgnore(any());
    }

    @Test
    void existingActiveSignupAtCapacityIsIdempotent() {
        OfficialEventParticipation row = new OfficialEventParticipation();
        row.setState(OfficialEventParticipation.STATE_ACTIVE);
        when(participation.selectParticipation(7L, 101L)).thenReturn(row);
        when(legacy.selectByEventAndMember(7L, 101L)).thenReturn(new EventSignup());
        assertNull(service.signup(7L, 101L));
        assertNull(service.signup(7L, 101L));
        verify(legacy, never()).insertIgnore(any());
        verify(participation, never()).insertParticipationIgnore(any());
        verify(participation, never()).countActiveParticipations(7L);
    }

    @Test
    void fullEventRejectsNewMemberWithoutLegacyWrite() {
        when(participation.countActiveParticipations(7L)).thenReturn(1);
        assertEquals("报名人数已满", service.signup(7L, 202L));
        verifyNoInteractions(legacy);
        verify(participation, never()).insertParticipationIgnore(any());
    }

    @Test
    void pausedEventRejectsWithoutSignupEffects() {
        event.setControlState("PAUSED");
        assertEquals("活动已暂停", service.signup(7L, 101L));
        verifyNoInteractions(legacy, participation);
    }
}
