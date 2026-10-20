package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubLeader;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.mapper.ClubLeaderMapper;
import com.chengyinhub.business.mapper.ClubMapper;
import com.chengyinhub.business.mapper.MerchantOperatorMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.service.impl.ClubServiceImpl;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * V5a SC-CL-APPROVE:被驳回后再申请,改回同一行为待审并清空驳回原因,不复制第二个俱乐部;
 * 被驳回的俱乐部不占每人 2 个的配额。
 */
class ClubRejectedResubmitTest {
    private static final long MEMBER_ID = 42L;
    private ClubMapper clubMapper;
    private ClubServiceImpl service;

    @BeforeEach
    void setUp() {
        clubMapper = mock(ClubMapper.class);
        ClubLeaderMapper leaderMapper = mock(ClubLeaderMapper.class);
        IUmsMemberService memberService = mock(IUmsMemberService.class);
        service = new ClubServiceImpl();
        ReflectionTestUtils.setField(service, "clubMapper", clubMapper);
        ReflectionTestUtils.setField(service, "clubLeaderMapper", leaderMapper);
        ReflectionTestUtils.setField(service, "mmsMerchantMapper", mock(MmsMerchantMapper.class));
        ReflectionTestUtils.setField(service, "merchantOperatorMapper", mock(MerchantOperatorMapper.class));
        ReflectionTestUtils.setField(service, "umsMemberService", memberService);
        when(memberService.lockMemberById(MEMBER_ID)).thenReturn(MEMBER_ID);
        UmsMember account = new UmsMember();
        account.setId(MEMBER_ID);
        account.setStatus(1L);
        account.setDelFlag(0);
        when(memberService.selectUmsMemberById(MEMBER_ID)).thenReturn(account);
        ClubLeader leader = new ClubLeader();
        leader.setId(7L);
        leader.setMemberId(MEMBER_ID);
        leader.setStatus(1);
        leader.setDelFlag(0);
        when(leaderMapper.selectClubLeaderByMemberId(MEMBER_ID)).thenReturn(leader);
        when(clubMapper.insertClub(any(Club.class))).thenReturn(1);
        when(clubMapper.updateClub(any(Club.class))).thenReturn(1);
    }

    @Test
    void resubmitAfterRejectionUpdatesTheSameRowBackToPendingAndClearsTheReason() {
        Club rejected = row(12L, "旧名字", 2L);
        rejected.setRejectReason("简介不完整");
        owned(rejected);
        Club resubmit = request("改好的名字");

        int count = service.insertClubWithinOwnerQuota(resubmit, 2);

        ArgumentCaptor<Club> update = ArgumentCaptor.forClass(Club.class);
        verify(clubMapper).updateClub(update.capture());
        verify(clubMapper, never()).insertClub(any());
        assertEquals(12L, update.getValue().getId());
        assertEquals(0L, update.getValue().getStatus());
        assertNull(update.getValue().getRejectReason());
        assertNull(update.getValue().getMemberCount(), "重新提交不能把已有成员数清零");
        assertEquals("改好的名字", update.getValue().getName());
        assertEquals(12L, resubmit.getId());
        assertEquals(1, count);
    }

    @Test
    void rejectedClubDoesNotCountTowardTheTwoClubQuota() {
        owned(row(11L, "在营", 1L), row(12L, "被驳回", 2L));

        int count = service.insertClubWithinOwnerQuota(request("第二个"), 2);

        assertEquals(2, count);
        verify(clubMapper).updateClub(any(Club.class));
    }

    @Test
    void twoActiveClubsStillHitTheQuotaEvenWithARejectedOneToResubmit() {
        owned(row(11L, "在营一", 1L), row(13L, "在营二", 1L), row(12L, "被驳回", 2L));

        assertThrows(ServiceException.class, () -> service.insertClubWithinOwnerQuota(request("第三个"), 2));

        verify(clubMapper, never()).updateClub(any());
        verify(clubMapper, never()).insertClub(any());
    }

    private void owned(Club... rows) {
        java.util.List<Long> ids = new java.util.ArrayList<>();
        for (Club row : rows) ids.add(row.getId());
        when(clubMapper.selectOwnedClubIdsForUpdate(MEMBER_ID)).thenReturn(ids);
        when(clubMapper.selectClubsByIds(ids)).thenReturn(Arrays.asList(rows));
    }

    private Club row(Long id, String name, Long status) {
        Club row = new Club();
        row.setId(id);
        row.setMemberId(MEMBER_ID);
        row.setName(name);
        row.setStatus(status);
        row.setMemberCount(5);
        row.setDelFlag(0);
        return row;
    }

    private Club request(String name) {
        Club club = new Club();
        club.setMemberId(MEMBER_ID);
        club.setName(name);
        club.setStatus(1L);
        club.setMemberCount(0);
        club.setDelFlag(0);
        return club;
    }
}
