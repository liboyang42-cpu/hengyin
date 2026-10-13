package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.ClubGovernanceBan;
import com.chengyinhub.business.mapper.ClubGovernanceMapper;
import com.chengyinhub.business.service.impl.ClubParticipationGuardImpl;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ClubParticipationGuardImplTest {
    private static final long CLUB = 11L;
    private static final long MEMBER = 902L;
    private ClubGovernanceMapper mapper;
    private ClubParticipationGuardImpl guard;

    @BeforeEach
    void setUp() {
        mapper = mock(ClubGovernanceMapper.class);
        guard = new ClubParticipationGuardImpl();
        ReflectionTestUtils.setField(guard, "clubGovernanceMapper", mapper);
    }

    @Test
    void activeClubAndPlatformBansBothBlock() {
        when(mapper.selectEffectiveByClubAndMember(eq(CLUB), eq(MEMBER), any(Date.class)))
                .thenReturn(ban(ClubGovernanceBan.SOURCE_CLUB, future(7)));
        ServiceException local = assertThrows(ServiceException.class,
                () -> guard.requireAllowed(CLUB, MEMBER));
        assertEquals(HttpStatus.FORBIDDEN, local.getCode());

        when(mapper.selectEffectiveByClubAndMember(eq(CLUB), eq(MEMBER), any(Date.class)))
                .thenReturn(ban(ClubGovernanceBan.SOURCE_PLATFORM, null));
        ServiceException platform = assertThrows(ServiceException.class,
                () -> guard.requireAllowed(CLUB, MEMBER));
        assertEquals(HttpStatus.FORBIDDEN, platform.getCode());
    }

    @Test
    void expiredClubBanAllowsParticipationButMalformedOrCrossClubRowsFailClosed() {
        when(mapper.selectEffectiveByClubAndMember(eq(CLUB), eq(MEMBER), any(Date.class)))
                .thenReturn(ban(ClubGovernanceBan.SOURCE_CLUB, new Date(System.currentTimeMillis() - 1_000L)));
        assertDoesNotThrow(() -> guard.requireAllowed(CLUB, MEMBER));

        ClubGovernanceBan crossClub = ban(ClubGovernanceBan.SOURCE_CLUB, future(7));
        crossClub.setClubId(12L);
        when(mapper.selectEffectiveByClubAndMember(eq(CLUB), eq(MEMBER), any(Date.class)))
                .thenReturn(crossClub);
        assertThrows(ServiceException.class, () -> guard.requireAllowed(CLUB, MEMBER));

        ClubGovernanceBan malformed = ban("UNKNOWN", future(7));
        when(mapper.selectEffectiveByClubAndMember(eq(CLUB), eq(MEMBER), any(Date.class)))
                .thenReturn(malformed);
        assertThrows(ServiceException.class, () -> guard.requireAllowed(CLUB, MEMBER));

        ClubGovernanceBan malformedPlatform = ban(ClubGovernanceBan.SOURCE_PLATFORM, future(7));
        when(mapper.selectEffectiveByClubAndMember(eq(CLUB), eq(MEMBER), any(Date.class)))
                .thenReturn(malformedPlatform);
        assertThrows(ServiceException.class, () -> guard.requireAllowed(CLUB, MEMBER));
    }

    @Test
    void invalidScopeFailsClosedBeforeTheMapperCanAccidentallyAllowIt() {
        ServiceException missingClub = assertThrows(ServiceException.class,
                () -> guard.requireAllowed(null, MEMBER));
        ServiceException missingMember = assertThrows(ServiceException.class,
                () -> guard.requireAllowed(CLUB, null));

        assertEquals(HttpStatus.FORBIDDEN, missingClub.getCode());
        assertEquals(HttpStatus.FORBIDDEN, missingMember.getCode());
    }

    private ClubGovernanceBan ban(String source, Date expiresAt) {
        ClubGovernanceBan row = new ClubGovernanceBan();
        row.setId(81L);
        row.setClubId(CLUB);
        row.setTargetMemberId(MEMBER);
        row.setStatus(ClubGovernanceBan.STATUS_ACTIVE);
        row.setSourceType(source);
        row.setExpiresAt(expiresAt);
        return row;
    }

    private Date future(int days) {
        return new Date(System.currentTimeMillis() + days * 86_400_000L);
    }
}
