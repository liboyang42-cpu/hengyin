package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.UmsMemberFollow;
import com.chengyinhub.business.mapper.UmsMemberFollowMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.impl.UmsMemberFollowServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R10-07 收尾:同一 follower 行锁内查重 + 写;存在性/自关注只拦建立新关系
 * (目标注销的遗留关注仍必须能取消/原样维护);后台 edit 先按升序锁 follower，再锁关系。
 */
class UmsMemberFollowServiceImplTest {

    private static final Long ME = 9L;
    private static final Long TARGET = 88L;

    private UmsMemberFollowMapper followMapper;
    private UmsMemberMapper memberMapper;
    private UmsMemberFollowServiceImpl service;

    @BeforeEach
    void setUp() {
        followMapper = mock(UmsMemberFollowMapper.class);
        memberMapper = mock(UmsMemberMapper.class);
        service = new UmsMemberFollowServiceImpl();
        ReflectionTestUtils.setField(service, "umsMemberFollowMapper", followMapper);
        ReflectionTestUtils.setField(service, "umsMemberMapper", memberMapper);
        when(memberMapper.lockMemberById(ME)).thenReturn(ME);
        when(followMapper.selectExistingMemberId(TARGET)).thenReturn(TARGET);
        when(followMapper.insertUmsMemberFollow(any())).thenReturn(1);
        when(followMapper.deleteUmsMemberFollowByIds(any())).thenReturn(1);
        when(followMapper.updateUmsMemberFollow(any())).thenReturn(1);
    }

    @Test
    void locksFollowerRowBeforeCheckingRelationAndEstablishing() {
        when(followMapper.selectUmsMemberFollowList(any())).thenReturn(Collections.emptyList());

        assertEquals(IUmsMemberFollowService.ApplyResult.FOLLOWED,
                service.applyFollowState(ME, TARGET, Boolean.TRUE));

        InOrder order = inOrder(memberMapper, followMapper);
        order.verify(memberMapper).lockMemberById(ME);
        order.verify(followMapper).selectUmsMemberFollowList(any());
        order.verify(followMapper).selectExistingMemberId(TARGET);
        order.verify(followMapper).insertUmsMemberFollow(any());
    }

    @Test
    void followDesiredWhenAlreadyFollowedIsNoopWithoutTargetLookup() {
        when(followMapper.selectUmsMemberFollowList(any())).thenReturn(Collections.singletonList(row(1L, ME, TARGET)));

        assertEquals(IUmsMemberFollowService.ApplyResult.ALREADY_FOLLOWED,
                service.applyFollowState(ME, TARGET, Boolean.TRUE));

        verify(followMapper, never()).insertUmsMemberFollow(any());
        verify(followMapper, never()).selectExistingMemberId(anyLong());
    }

    @Test
    void followDesiredInsertsWhenAbsent() {
        when(followMapper.selectUmsMemberFollowList(any())).thenReturn(Collections.emptyList());

        assertEquals(IUmsMemberFollowService.ApplyResult.FOLLOWED,
                service.applyFollowState(ME, TARGET, Boolean.TRUE));

        verify(followMapper).insertUmsMemberFollow(any());
        verify(followMapper, never()).deleteUmsMemberFollowByIds(any());
    }

    @Test
    void selfFollowRejectedOnlyWhenEstablishing() {
        when(followMapper.selectUmsMemberFollowList(any())).thenReturn(Collections.emptyList());

        assertEquals(IUmsMemberFollowService.ApplyResult.SELF_FOLLOW,
                service.applyFollowState(ME, ME, Boolean.TRUE));

        verify(followMapper, never()).insertUmsMemberFollow(any());
    }

    @Test
    void missingTargetRejectedOnlyWhenEstablishing() {
        when(followMapper.selectExistingMemberId(TARGET)).thenReturn(null);
        when(followMapper.selectUmsMemberFollowList(any())).thenReturn(Collections.emptyList());

        assertEquals(IUmsMemberFollowService.ApplyResult.INVALID_MEMBER,
                service.applyFollowState(ME, TARGET, Boolean.TRUE));

        verify(followMapper, never()).insertUmsMemberFollow(any());
    }

    @Test
    void unfollowStillWorksWhenTargetIsGone() {
        when(followMapper.selectExistingMemberId(TARGET)).thenReturn(null);
        when(followMapper.selectUmsMemberFollowList(any())).thenReturn(Collections.singletonList(row(1L, ME, TARGET)));

        assertEquals(IUmsMemberFollowService.ApplyResult.UNFOLLOWED,
                service.applyFollowState(ME, TARGET, Boolean.FALSE));

        verify(followMapper).deleteUmsMemberFollowByIds(any(Long[].class));
        verify(followMapper, never()).selectExistingMemberId(anyLong());
    }

    @Test
    void legacyToggleCanRemoveWhenTargetIsGone() {
        when(followMapper.selectExistingMemberId(TARGET)).thenReturn(null);
        when(followMapper.selectUmsMemberFollowList(any())).thenReturn(Collections.singletonList(row(1L, ME, TARGET)));

        assertEquals(IUmsMemberFollowService.ApplyResult.UNFOLLOWED,
                service.applyFollowState(ME, TARGET, null));

        verify(followMapper).deleteUmsMemberFollowByIds(any(Long[].class));
    }

    @Test
    void unfollowDesiredWhenAbsentIsNoop() {
        when(followMapper.selectUmsMemberFollowList(any())).thenReturn(Collections.emptyList());

        assertEquals(IUmsMemberFollowService.ApplyResult.ALREADY_UNFOLLOWED,
                service.applyFollowState(ME, TARGET, Boolean.FALSE));

        verify(followMapper, never()).insertUmsMemberFollow(any());
    }

    @Test
    void editUsesAscendingFollowerLocksBeforeRelationRow() {
        when(followMapper.selectUmsMemberFollowById(5L)).thenReturn(row(5L, ME, 300L));
        when(followMapper.selectUmsMemberFollowByIdForUpdate(5L)).thenReturn(row(5L, ME, 300L));
        when(followMapper.selectUmsMemberFollowList(any())).thenReturn(Collections.emptyList());

        UmsMemberFollow update = new UmsMemberFollow();
        update.setId(5L);
        update.setMemberId(TARGET);
        when(followMapper.selectExistingMemberId(300L)).thenReturn(300L);
        when(memberMapper.lockMemberById(ME)).thenReturn(ME);
        when(memberMapper.lockMemberById(TARGET)).thenReturn(TARGET);

        IUmsMemberFollowService.EditResult result = service.editUmsMemberFollowLocked(update);

        assertEquals(IUmsMemberFollowService.EditResult.Kind.UPDATED, result.getKind());
        InOrder order = inOrder(followMapper, memberMapper);
        order.verify(followMapper).selectUmsMemberFollowById(5L);
        order.verify(memberMapper).lockMemberById(ME);
        order.verify(memberMapper).lockMemberById(TARGET);
        order.verify(followMapper).selectUmsMemberFollowByIdForUpdate(5L);
        verify(followMapper).updateUmsMemberFollow(any());
    }

    @Test
    void editRejectsPairThatWouldDuplicateAnotherRow() {
        when(followMapper.selectUmsMemberFollowById(5L)).thenReturn(row(5L, ME, 300L));
        when(followMapper.selectUmsMemberFollowByIdForUpdate(5L)).thenReturn(row(5L, ME, 300L));
        when(followMapper.selectUmsMemberFollowList(any())).thenReturn(Collections.singletonList(row(7L, ME, TARGET)));

        UmsMemberFollow update = new UmsMemberFollow();
        update.setId(5L);
        update.setFollowMemberId(TARGET);

        assertEquals(IUmsMemberFollowService.EditResult.Kind.CONFLICT,
                service.editUmsMemberFollowLocked(update).getKind());
        verify(followMapper, never()).updateUmsMemberFollow(any());
    }

    @Test
    void editNotFoundFailsClosed() {
        when(followMapper.selectUmsMemberFollowByIdForUpdate(5L)).thenReturn(null);

        UmsMemberFollow update = new UmsMemberFollow();
        update.setId(5L);
        update.setFollowMemberId(TARGET);

        assertEquals(IUmsMemberFollowService.EditResult.Kind.NOT_FOUND,
                service.editUmsMemberFollowLocked(update).getKind());
        verify(memberMapper, never()).lockMemberById(anyLong());
    }

    @Test
    void editSelfFollowRejectedWhenPairChanging() {
        when(followMapper.selectUmsMemberFollowById(5L)).thenReturn(row(5L, ME, 300L));
        when(followMapper.selectUmsMemberFollowByIdForUpdate(5L)).thenReturn(row(5L, ME, 300L));

        UmsMemberFollow update = new UmsMemberFollow();
        update.setId(5L);
        update.setFollowMemberId(ME);

        assertEquals(IUmsMemberFollowService.EditResult.Kind.SELF_FOLLOW,
                service.editUmsMemberFollowLocked(update).getKind());
        verify(followMapper, never()).updateUmsMemberFollow(any());
    }

    @Test
    void editMissingTargetRejectedWhenPairChanging() {
        when(followMapper.selectUmsMemberFollowById(5L)).thenReturn(row(5L, ME, 300L));
        when(followMapper.selectUmsMemberFollowByIdForUpdate(5L)).thenReturn(row(5L, ME, 300L));
        when(followMapper.selectExistingMemberId(999L)).thenReturn(null);

        UmsMemberFollow update = new UmsMemberFollow();
        update.setId(5L);
        update.setFollowMemberId(999L);

        assertEquals(IUmsMemberFollowService.EditResult.Kind.INVALID_MEMBER,
                service.editUmsMemberFollowLocked(update).getKind());
    }

    @Test
    void editUnchangedPairAllowsGoneTarget() {
        when(followMapper.selectUmsMemberFollowById(5L)).thenReturn(row(5L, ME, 300L));
        when(followMapper.selectUmsMemberFollowByIdForUpdate(5L)).thenReturn(row(5L, ME, 300L));
        when(followMapper.selectUmsMemberFollowList(any())).thenReturn(Collections.emptyList());

        UmsMemberFollow update = new UmsMemberFollow();
        update.setId(5L);
        update.setUpdateBy("ops-touch");

        assertEquals(IUmsMemberFollowService.EditResult.Kind.UPDATED,
                service.editUmsMemberFollowLocked(update).getKind());
        verify(followMapper, never()).selectExistingMemberId(anyLong());
        verify(followMapper).updateUmsMemberFollow(any());
    }

    @Test
    void ownerChangedWhileWaitingRequiresRefreshWithoutWriting() {
        when(followMapper.selectUmsMemberFollowById(5L)).thenReturn(row(5L, ME, 300L));
        when(followMapper.selectUmsMemberFollowByIdForUpdate(5L)).thenReturn(row(5L, TARGET, 300L));
        UmsMemberFollow update = new UmsMemberFollow();
        update.setId(5L);
        update.setFollowMemberId(400L);
        assertEquals(IUmsMemberFollowService.EditResult.Kind.CONCURRENT_CHANGE,
                service.editUmsMemberFollowLocked(update).getKind());
        verify(followMapper, never()).updateUmsMemberFollow(any());
        verify(memberMapper, never()).lockMemberById(TARGET);
    }

    private UmsMemberFollow row(Long id, Long memberId, Long followMemberId) {
        UmsMemberFollow row = new UmsMemberFollow();
        row.setId(id);
        row.setMemberId(memberId);
        row.setFollowMemberId(followMemberId);
        return row;
    }
}
