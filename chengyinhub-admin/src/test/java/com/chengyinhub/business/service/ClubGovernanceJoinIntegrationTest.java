package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.ClubMemberMapper;
import com.chengyinhub.business.service.impl.ClubMemberServiceImpl;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClubGovernanceJoinIntegrationTest {
    private static final long CLUB = 11L;
    private static final long MEMBER = 902L;
    private static final long OWNER = 900L;

    private ClubMapper clubs;
    private ClubMemberMapper members;
    private IClubParticipationGuard participationGuard;
    private ClubMemberServiceImpl service;

    @BeforeEach
    void setUp() {
        clubs = mock(ClubMapper.class);
        members = mock(ClubMemberMapper.class);
        participationGuard = mock(IClubParticipationGuard.class);
        service = new ClubMemberServiceImpl();
        ReflectionTestUtils.setField(service, "clubMapper", clubs);
        ReflectionTestUtils.setField(service, "clubMemberMapper", members);
        ReflectionTestUtils.setField(service, "clubParticipationGuard", participationGuard);
        Club active = new Club();
        active.setId(CLUB);
        active.setMemberId(OWNER);
        active.setStatus(1L);
        active.setDelFlag(0);
        when(clubs.selectClubByIdForUpdate(CLUB)).thenReturn(active);
        org.mockito.Mockito.doThrow(new ServiceException("你当前无法参与该俱乐部", HttpStatus.FORBIDDEN))
                .when(participationGuard).requireAllowed(CLUB, MEMBER);
    }

    @Test
    void directJoinAndJoinRequestStopBeforeAnyMembershipWrite() {
        ClubMember joining = new ClubMember();
        joining.setClubId(CLUB);
        joining.setMemberId(MEMBER);
        assertThrows(ServiceException.class, () -> service.joinMember(joining));
        assertThrows(ServiceException.class, () -> service.requestJoin(CLUB, MEMBER));
        verify(members, never()).insertClubMember(any());
        verify(members, never()).updateMembershipState(any(), any(), any(), any());
        verify(clubs, never()).updateMemberCount(any(), anyInt());
    }

    @Test
    void approvalRechecksTheBanSoAPendingRequestCannotRaceIntoMembership() {
        ClubMember pending = new ClubMember();
        pending.setId(71L);
        pending.setClubId(CLUB);
        pending.setMemberId(MEMBER);
        pending.setStatus(0);
        when(members.selectAnyByClubAndMember(CLUB, MEMBER)).thenReturn(pending);

        assertThrows(ServiceException.class,
                () -> service.reviewJoinRequest(CLUB, MEMBER, OWNER, true));

        verify(participationGuard).requireAllowed(CLUB, MEMBER);
        verify(members, never()).updateMembershipState(any(), any(), any(), any());
        verify(clubs, never()).updateMemberCount(any(), anyInt());
    }
}
