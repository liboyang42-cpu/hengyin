package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.Club;
import com.chengyinhub.business.domain.ClubMember;
import com.chengyinhub.business.service.IClubMemberService;
import com.chengyinhub.business.service.IClubLifecycleCommandService;
import com.chengyinhub.business.service.IClubService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiClubImCleanupGateTest {

    private ApiClubController controller;
    private IClubMemberService memberService;
    private IClubService clubService;
    private IClubLifecycleCommandService lifecycleService;

    @BeforeEach
    void setUp() {
        controller = spy(new ApiClubController());
        doReturn(7L).when(controller).getAppUserId();
        memberService = mock(IClubMemberService.class);
        clubService = mock(IClubService.class);
        lifecycleService = mock(IClubLifecycleCommandService.class);
        ReflectionTestUtils.setField(controller, "clubMemberService", memberService);
        ReflectionTestUtils.setField(controller, "clubService", clubService);
        ReflectionTestUtils.setField(controller, "clubLifecycleCommandService", lifecycleService);
    }

    @Test
    void removeMemberReportsImCleanupFailureInsteadOfReturningSuccess() {
        Club club = new Club();
        club.setId(42L);
        club.setMemberId(7L);
        when(clubService.selectClubById(42L)).thenReturn(club);
        // 2026-08-26:移除成员的归属校验改走 canGovernClub(owner 或 role=1),mock 要显式放行
        when(memberService.canGovernClub(club, 7L)).thenReturn(true);
        ClubMember existing = new ClubMember();
        existing.setClubId(42L);
        existing.setMemberId(8L);
        when(memberService.selectByClubAndMember(42L, 8L)).thenReturn(existing);
        when(lifecycleService.removeMember(eq(42L), eq(8L), eq(7L), anyString()))
                .thenThrow(new RuntimeException("IM_MARK_DELETED_FAILED"));
        ClubMember request = new ClubMember();
        request.setClubId(42L);
        request.setMemberId(8L);

        AjaxResult result = controller.removeMember(request);

        assertEquals("移除失败，群聊权限清理未完成", result.get("msg"));
        verify(lifecycleService).removeMember(eq(42L), eq(8L), eq(7L), anyString());
    }

    @Test
    void quitReportsImCleanupFailureInsteadOfReturningSuccess() {
        Club club = new Club();
        club.setId(42L);
        club.setMemberId(9L);
        when(clubService.selectClubById(42L)).thenReturn(club);
        when(lifecycleService.quit(eq(42L), eq(7L), anyString()))
                .thenThrow(new RuntimeException("IM_MARK_DELETED_FAILED"));
        Club request = new Club();
        request.setId(42L);

        AjaxResult result = controller.quit(request);

        assertEquals("退出失败，群聊权限清理未完成", result.get("msg"));
        verify(lifecycleService).quit(eq(42L), eq(7L), anyString());
    }
}
