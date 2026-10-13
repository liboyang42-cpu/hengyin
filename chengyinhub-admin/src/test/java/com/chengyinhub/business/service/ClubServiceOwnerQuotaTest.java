package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubLeader;
import com.chengyinhub.business.domain.MerchantOperator;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.ClubLeaderMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.MerchantOperatorMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.service.impl.ClubServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ClubServiceOwnerQuotaTest {
    private static final long MEMBER_ID = 42L;
    private ClubMapper clubMapper;
    private ClubLeaderMapper leaderMapper;
    private MmsMerchantMapper merchantMapper;
    private MerchantOperatorMapper operatorMapper;
    private IUmsMemberService memberService;
    private ClubServiceImpl service;

    @BeforeEach
    void setUp() {
        clubMapper = mock(ClubMapper.class);
        leaderMapper = mock(ClubLeaderMapper.class);
        merchantMapper = mock(MmsMerchantMapper.class);
        operatorMapper = mock(MerchantOperatorMapper.class);
        memberService = mock(IUmsMemberService.class);
        service = new ClubServiceImpl();
        ReflectionTestUtils.setField(service, "clubMapper", clubMapper);
        ReflectionTestUtils.setField(service, "clubLeaderMapper", leaderMapper);
        ReflectionTestUtils.setField(service, "mmsMerchantMapper", merchantMapper);
        ReflectionTestUtils.setField(service, "merchantOperatorMapper", operatorMapper);
        ReflectionTestUtils.setField(service, "umsMemberService", memberService);
        when(memberService.lockMemberById(MEMBER_ID)).thenReturn(MEMBER_ID);
        when(memberService.selectUmsMemberById(MEMBER_ID)).thenReturn(activeMember());
        when(leaderMapper.selectClubLeaderByMemberId(MEMBER_ID)).thenReturn(activeLeader());
        when(clubMapper.selectOwnedClubIdsForUpdate(MEMBER_ID)).thenReturn(Collections.emptyList());
        when(clubMapper.insertClub(any(Club.class))).thenReturn(1);
    }

    @Test
    void createWithinQuotaLocksOwnerBeforeCurrentCountAndInsert() {
        Club club = club();

        int newCount = service.insertClubWithinOwnerQuota(club, 2);

        assertEquals(1, newCount);
        assertNotNull(club.getCreateTime());
        InOrder writes = inOrder(memberService, clubMapper);
        writes.verify(memberService).lockMemberById(MEMBER_ID);
        writes.verify(clubMapper).selectOwnedClubIdsForUpdate(MEMBER_ID);
        writes.verify(clubMapper).insertClub(club);
    }

    @Test
    void createAtQuotaFailsBeforeInsertWhileHoldingTheSameOwnerLock() {
        when(clubMapper.selectOwnedClubIdsForUpdate(MEMBER_ID)).thenReturn(Arrays.asList(11L, 12L));

        assertThrows(ServiceException.class, () -> service.insertClubWithinOwnerQuota(club(), 2));

        verify(memberService).lockMemberById(MEMBER_ID);
        verify(clubMapper, never()).insertClub(any());
    }

    @Test
    void missingOwnerLockFailsClosedBeforeCountingOrWriting() {
        when(memberService.lockMemberById(MEMBER_ID)).thenReturn(null);

        assertThrows(ServiceException.class, () -> service.insertClubWithinOwnerQuota(club(), 2));

        verify(clubMapper, never()).selectOwnedClubIdsForUpdate(MEMBER_ID);
        verify(clubMapper, never()).insertClub(any());
    }

    @Test
    void activeMerchantOwnerCannotCreateEvenWithLegacyActiveLeaderProfile() {
        when(merchantMapper.countMmsMerchantRowsByMemberIdForUpdate(MEMBER_ID)).thenReturn(1);

        ServiceException error = assertThrows(
                ServiceException.class, () -> service.insertClubWithinOwnerQuota(club(), 2));

        assertEquals(409, error.getCode());
        verify(clubMapper, never()).selectOwnedClubIdsForUpdate(MEMBER_ID);
        verify(clubMapper, never()).insertClub(any());
    }

    @Test
    void pendingMerchantApplicationCannotCreateEvenWithLegacyActiveLeaderProfile() {
        when(merchantMapper.countMmsMerchantRowsByMemberIdForUpdate(MEMBER_ID)).thenReturn(1);

        ServiceException error = assertThrows(
                ServiceException.class, () -> service.insertClubWithinOwnerQuota(club(), 2));

        assertEquals(409, error.getCode());
        verify(clubMapper, never()).selectOwnedClubIdsForUpdate(MEMBER_ID);
        verify(clubMapper, never()).insertClub(any());
    }

    @Test
    void rejectedReopenableMerchantApplicationCannotCreateWithLegacyLeaderProfile() {
        when(merchantMapper.countMmsMerchantRowsByMemberIdForUpdate(MEMBER_ID)).thenReturn(1);

        ServiceException error = assertThrows(
                ServiceException.class, () -> service.insertClubWithinOwnerQuota(club(), 2));

        assertEquals(409, error.getCode());
        verify(clubMapper, never()).selectOwnedClubIdsForUpdate(MEMBER_ID);
        verify(clubMapper, never()).insertClub(any());
    }

    @Test
    void activeMerchantOperatorCannotCreateEvenWithLegacyActiveLeaderProfile() {
        MerchantOperator active = new MerchantOperator();
        active.setOperatorMemberId(MEMBER_ID);
        active.setStatus(MerchantOperator.STATUS_ACTIVE);
        when(operatorMapper.selectActiveByMemberId(MEMBER_ID)).thenReturn(active);

        ServiceException error = assertThrows(
                ServiceException.class, () -> service.insertClubWithinOwnerQuota(club(), 2));

        assertEquals(409, error.getCode());
        verify(clubMapper, never()).selectOwnedClubIdsForUpdate(MEMBER_ID);
        verify(clubMapper, never()).insertClub(any());
    }

    private Club club() {
        Club club = new Club();
        club.setMemberId(MEMBER_ID);
        club.setName("并发配额俱乐部");
        club.setStatus(1L);
        club.setDelFlag(0);
        return club;
    }

    private UmsMember activeMember() {
        UmsMember member = new UmsMember();
        member.setId(MEMBER_ID);
        member.setStatus(1L);
        member.setDelFlag(0);
        return member;
    }

    private ClubLeader activeLeader() {
        ClubLeader leader = new ClubLeader();
        leader.setId(7L);
        leader.setMemberId(MEMBER_ID);
        leader.setStatus(1);
        leader.setDelFlag(0);
        return leader;
    }

}
