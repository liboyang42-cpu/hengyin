package com.chengyinhub.business.service.orienteering;

import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubParticipationGuard;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TeamClubEligibilityAuthorityTest {

    private static final long CLUB_A = 11L;
    private static final long CLUB_B = 22L;
    private static final long OWNER_A = 501L;
    private static final long MEMBER_A = 502L;
    private static final long MEMBER_B = 602L;

    private IClubService clubService;
    private IClubMemberService clubMemberService;
    private IClubParticipationGuard guard;
    private TeamClubEligibilityAuthority authority;

    @BeforeEach
    void setUp() {
        clubService = mock(IClubService.class);
        clubMemberService = mock(IClubMemberService.class);
        guard = mock(IClubParticipationGuard.class);
        authority = new TeamClubEligibilityAuthority(clubService, clubMemberService, guard);
        when(clubService.selectClubById(CLUB_A)).thenReturn(club(CLUB_A, OWNER_A));
        when(clubService.selectClubById(CLUB_B)).thenReturn(club(CLUB_B, 601L));
        when(clubMemberService.selectByClubAndMember(CLUB_A, OWNER_A)).thenReturn(null);
        when(clubMemberService.selectByClubAndMember(CLUB_A, MEMBER_A)).thenReturn(member(CLUB_A, MEMBER_A));
        when(clubMemberService.selectByClubAndMember(CLUB_B, MEMBER_B)).thenReturn(member(CLUB_B, MEMBER_B));
    }

    @Test
    void teamFromRealMultiClubAuthorityRegistersAndReadbackReturnsOriginal() {
        CmsTopic course = PublishedRuleSelectorTest.publishedCity(7L, 9L);
        TeamClubEligibilityAuthority.Registration registration = authority.register(course,
                TeamClubEligibilityAuthority.EntryMode.TEAM,
                List.of(OWNER_A, MEMBER_A, MEMBER_B), List.of(CLUB_A, CLUB_B), "reg-team-01");
        assertEquals(List.of(OWNER_A, MEMBER_A, MEMBER_B), registration.memberIds);
        assertEquals(CLUB_A, registration.memberClubId.get(OWNER_A));
        assertEquals(CLUB_A, registration.memberClubId.get(MEMBER_A));
        assertEquals(CLUB_B, registration.memberClubId.get(MEMBER_B));
        assertTrue(registration.authorizedClubIdsUsed.containsAll(List.of(CLUB_A, CLUB_B)));
        assertSame(registration, authority.readback("reg-team-01"));
        assertSame(registration, authority.register(course, TeamClubEligibilityAuthority.EntryMode.TEAM,
                List.of(OWNER_A, MEMBER_A, MEMBER_B), List.of(CLUB_A, CLUB_B), "reg-team-01"));
    }

    @Test
    void pendingMemberBannedMemberAndOutsiderAreNotEligible() {
        CmsTopic course = PublishedRuleSelectorTest.publishedCity(7L, 9L);
        when(clubMemberService.selectByClubAndMember(CLUB_A, 777L)).thenReturn(null);
        assertEquals(OrienteeringEventException.TEAM_INELIGIBLE,
                assertThrows(OrienteeringEventException.class, () -> authority.register(course,
                        TeamClubEligibilityAuthority.EntryMode.TEAM, List.of(OWNER_A, 777L),
                        List.of(CLUB_A), "reg-out-01")).getErrorCode());

        ClubMember pending = member(CLUB_A, 778L);
        pending.setStatus(0);
        when(clubMemberService.selectByClubAndMember(CLUB_A, 778L)).thenReturn(pending);
        assertEquals(OrienteeringEventException.TEAM_INELIGIBLE,
                assertThrows(OrienteeringEventException.class, () -> authority.register(course,
                        TeamClubEligibilityAuthority.EntryMode.TEAM, List.of(OWNER_A, 778L),
                        List.of(CLUB_A), "reg-pend-01")).getErrorCode());

        doThrow(new ServiceException("你当前无法参与该俱乐部", HttpStatus.FORBIDDEN))
                .when(guard).requireAllowed(CLUB_A, MEMBER_A);
        assertEquals(OrienteeringEventException.TEAM_INELIGIBLE,
                assertThrows(OrienteeringEventException.class, () -> authority.register(course,
                        TeamClubEligibilityAuthority.EntryMode.TEAM, List.of(OWNER_A, MEMBER_A),
                        List.of(CLUB_A), "reg-ban-01")).getErrorCode());
    }

    @Test
    void emptyRosterDoesNotInventARandomTeamAndMatchmakingStaysCancelled() {
        CmsTopic course = PublishedRuleSelectorTest.publishedCity(7L, 9L);
        assertEquals(OrienteeringEventException.MATCHMAKING_CANCELLED,
                assertThrows(OrienteeringEventException.class, () -> authority.register(course,
                        TeamClubEligibilityAuthority.EntryMode.TEAM, List.of(), List.of(CLUB_A),
                        "reg-empty01")).getErrorCode());
        assertEquals(OrienteeringEventException.MATCHMAKING_CANCELLED,
                assertThrows(OrienteeringEventException.class,
                        () -> authority.refuseRandomMatchmaking(course, List.of(CLUB_A), "reg-rand-01")).getErrorCode());
        assertEquals(OrienteeringEventException.CLUB_AUTHORITY_MISSING,
                assertThrows(OrienteeringEventException.class, () -> authority.register(course,
                        TeamClubEligibilityAuthority.EntryMode.TEAM, List.of(OWNER_A, MEMBER_A), List.of(),
                        "reg-noclub1")).getErrorCode());
    }

    @Test
    void unknownRegistrationReadbackDoesNotInventARequest() {
        assertEquals(OrienteeringEventException.NOT_FOUND,
                assertThrows(OrienteeringEventException.class, () -> authority.readback("reg-missing")).getErrorCode());
    }

    @Test
    void recoverOriginalOfNeverSeenIdDoesNotOccupyThenUniqueRegisterApplies() {
        CmsTopic course = PublishedRuleSelectorTest.publishedCity(7L, 9L);
        TeamClubEligibilityAuthority.OriginalReceipt blank = authority.recoverOriginal(null);
        assertNull(blank.originalRequestId);
        assertEquals(TeamClubEligibilityAuthority.OUTCOME_NOT_FOUND, blank.outcome);
        assertEquals(false, blank.mintedNewRequest);
        TeamClubEligibilityAuthority.OriginalReceipt neverReg = authority.recoverOriginal("c04-reg-never");
        assertEquals("c04-reg-never", neverReg.originalRequestId);
        assertEquals(TeamClubEligibilityAuthority.OUTCOME_NOT_FOUND, neverReg.outcome);
        assertEquals(false, neverReg.mintedNewRequest);
        assertNull(neverReg.registration);
        assertEquals(OrienteeringEventException.NOT_FOUND,
                assertThrows(OrienteeringEventException.class, () -> authority.readback("c04-reg-never")).getErrorCode());
        TeamClubEligibilityAuthority.Registration first = authority.register(course,
                TeamClubEligibilityAuthority.EntryMode.TEAM,
                List.of(OWNER_A, MEMBER_A), List.of(CLUB_A), "c04-reg-never");
        assertEquals("c04-reg-never", first.requestId);
        assertEquals(TeamClubEligibilityAuthority.OUTCOME_REGISTERED,
                authority.recoverOriginal("c04-reg-never").outcome);
        assertSame(first, authority.readback("c04-reg-never"));
        assertSame(first, authority.register(course, TeamClubEligibilityAuthority.EntryMode.TEAM,
                List.of(OWNER_A, MEMBER_A), List.of(CLUB_A), "c04-reg-never"));
    }

    @Test
    void unusedIneligibleRegisterDoesNotOccupyThenUniqueRegisterApplies() {
        CmsTopic course = PublishedRuleSelectorTest.publishedCity(7L, 9L);
        when(clubMemberService.selectByClubAndMember(CLUB_A, 777L)).thenReturn(null);
        assertEquals(OrienteeringEventException.TEAM_INELIGIBLE,
                assertThrows(OrienteeringEventException.class, () -> authority.register(course,
                        TeamClubEligibilityAuthority.EntryMode.TEAM, List.of(OWNER_A, 777L),
                        List.of(CLUB_A), "c04-reg-inelig")).getErrorCode());
        TeamClubEligibilityAuthority.OriginalReceipt unused = authority.recoverOriginal("c04-reg-inelig");
        assertEquals("c04-reg-inelig", unused.originalRequestId);
        assertEquals(TeamClubEligibilityAuthority.OUTCOME_NOT_FOUND, unused.outcome);
        assertEquals(false, unused.mintedNewRequest);
        assertNull(unused.registration);
        assertEquals(OrienteeringEventException.NOT_FOUND,
                assertThrows(OrienteeringEventException.class, () -> authority.readback("c04-reg-inelig"))
                        .getErrorCode());
        TeamClubEligibilityAuthority.Registration first = authority.register(course,
                TeamClubEligibilityAuthority.EntryMode.TEAM,
                List.of(OWNER_A, MEMBER_A), List.of(CLUB_A), "c04-reg-inelig");
        assertEquals("c04-reg-inelig", first.requestId);
        assertEquals(TeamClubEligibilityAuthority.OUTCOME_REGISTERED,
                authority.recoverOriginal("c04-reg-inelig").outcome);
        assertSame(first, authority.readback("c04-reg-inelig"));
        assertSame(first, authority.register(course, TeamClubEligibilityAuthority.EntryMode.TEAM,
                List.of(OWNER_A, MEMBER_A), List.of(CLUB_A), "c04-reg-inelig"));
    }

    static Club club(long id, long ownerId) {
        Club club = new Club();
        club.setId(id);
        club.setMemberId(ownerId);
        club.setStatus(1L);
        club.setDelFlag(0);
        club.setCity("上海");
        return club;
    }

    static ClubMember member(long clubId, long memberId) {
        ClubMember row = new ClubMember();
        row.setClubId(clubId);
        row.setMemberId(memberId);
        row.setStatus(1);
        row.setDelFlag(0);
        row.setRole(0);
        return row;
    }
}
