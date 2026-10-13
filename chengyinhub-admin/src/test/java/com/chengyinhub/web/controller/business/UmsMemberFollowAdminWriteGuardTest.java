package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.UmsMemberFollow;
import com.chengyinhub.business.service.IUmsMemberFollowService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R10-07 写入口审计:后台新增/编辑关注也不得绕过唯一关系键。
 * 新增走同一把行锁 + 幂等;编辑改键时若撞已有关系必须拒绝,不造重复行。
 */
class UmsMemberFollowAdminWriteGuardTest {

    private UmsMemberFollowController controller;
    private IUmsMemberFollowService followService;

    @BeforeEach
    void setUp() {
        controller = new UmsMemberFollowController();
        followService = mock(IUmsMemberFollowService.class);
        ReflectionTestUtils.setField(controller, "umsMemberFollowService", followService);
    }

    @Test
    void adminAddGoesThroughLockedIdempotentPath() {
        UmsMemberFollow row = pair(null, 9L, 88L);
        when(followService.applyFollowState(9L, 88L, Boolean.TRUE))
                .thenReturn(IUmsMemberFollowService.ApplyResult.FOLLOWED);

        AjaxResult result = controller.add(row);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(followService).applyFollowState(9L, 88L, Boolean.TRUE);
        verify(followService, never()).insertUmsMemberFollow(any());
    }

    @Test
    void adminAddAlreadyExistingIsIdempotentSuccess() {
        when(followService.applyFollowState(9L, 88L, Boolean.TRUE))
                .thenReturn(IUmsMemberFollowService.ApplyResult.ALREADY_FOLLOWED);

        AjaxResult result = controller.add(pair(null, 9L, 88L));

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(followService, never()).insertUmsMemberFollow(any());
    }

    @Test
    void adminAddInvalidMemberFailsClosed() {
        when(followService.applyFollowState(9L, 88L, Boolean.TRUE))
                .thenReturn(IUmsMemberFollowService.ApplyResult.INVALID_MEMBER);

        assertEquals(500, controller.add(pair(null, 9L, 88L)).get(AjaxResult.CODE_TAG));
    }

    @Test
    void adminEditDelegatesToLockedServiceAndRejectsConflict() {
        when(followService.editUmsMemberFollowLocked(any()))
                .thenReturn(new IUmsMemberFollowService.EditResult(IUmsMemberFollowService.EditResult.Kind.CONFLICT));

        AjaxResult result = controller.edit(pair(5L, 9L, 88L));

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertTrue(String.valueOf(result.get(AjaxResult.MSG_TAG)).contains("已存在"));
        verify(followService).editUmsMemberFollowLocked(any());
        verify(followService, never()).updateUmsMemberFollow(any());
    }

    @Test
    void adminEditDelegatesToLockedServiceAndReportsUpdated() {
        when(followService.editUmsMemberFollowLocked(any()))
                .thenReturn(new IUmsMemberFollowService.EditResult(IUmsMemberFollowService.EditResult.Kind.UPDATED));

        AjaxResult result = controller.edit(pair(5L, null, null));

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        verify(followService).editUmsMemberFollowLocked(any());
    }

    @Test
    void adminEditMissingRowFailsClosed() {
        when(followService.editUmsMemberFollowLocked(any()))
                .thenReturn(new IUmsMemberFollowService.EditResult(IUmsMemberFollowService.EditResult.Kind.NOT_FOUND));

        assertEquals(500, controller.edit(pair(5L, 9L, 88L)).get(AjaxResult.CODE_TAG));
        verify(followService, never()).updateUmsMemberFollow(any());
    }

    @Test
    void adminEditConcurrentChangeShowsRefreshWithoutSuccess() {
        when(followService.editUmsMemberFollowLocked(any())).thenReturn(
                new IUmsMemberFollowService.EditResult(IUmsMemberFollowService.EditResult.Kind.CONCURRENT_CHANGE));
        AjaxResult result = controller.edit(pair(5L, 9L, 88L));
        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertTrue(String.valueOf(result.get(AjaxResult.MSG_TAG)).contains("刷新后重试"));
    }

    private UmsMemberFollow pair(Long id, Long memberId, Long followedId) {
        UmsMemberFollow row = new UmsMemberFollow();
        row.setId(id);
        row.setMemberId(memberId);
        row.setFollowMemberId(followedId);
        return row;
    }
}
