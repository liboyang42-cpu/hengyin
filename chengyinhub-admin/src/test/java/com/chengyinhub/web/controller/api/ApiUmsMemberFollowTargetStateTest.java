package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.ICommunityPostService;
import com.chengyinhub.business.service.IUmsMemberFollowService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * R10-07:控制器只做参数校验与结果映射,幂等/并发语义由 Service 行锁事务保证。
 * follow=1 期望关注、follow=0 期望取消、缺省 null=旧 toggle;非法值 fail-closed。
 */
class ApiUmsMemberFollowTargetStateTest {

    private static final Long ME = 9L;
    private static final String TARGET = "88";

    private static class StubController extends ApiUmsMemberController {
        private final Long me;
        StubController(Long me) { this.me = me; }
        @Override
        public Long getAppUserId() { return me; }
    }

    private StubController controller;
    private IUmsMemberFollowService followService;

    @BeforeEach
    void setUp() {
        controller = new StubController(ME);
        followService = mock(IUmsMemberFollowService.class);
        ReflectionTestUtils.setField(controller, "umsMemberFollowService", followService);
        // FOLLOWED 分支会发站内关注通知,合并后必须注入,否则 NPE。
        ReflectionTestUtils.setField(controller, "communityPostService", mock(ICommunityPostService.class));
    }

    @Test
    void followIntentDelegatesTrueAndReportsFollowSuccess() {
        when(followService.applyFollowState(ME, 88L, Boolean.TRUE))
                .thenReturn(IUmsMemberFollowService.ApplyResult.FOLLOWED);

        AjaxResult result = controller.userFollowActoin(TARGET, "1");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals("关注成功", result.get(AjaxResult.MSG_TAG));
        verify(followService).applyFollowState(ME, 88L, Boolean.TRUE);
    }

    @Test
    void alreadyFollowedIsStillSuccessWithoutReverse() {
        when(followService.applyFollowState(ME, 88L, Boolean.TRUE))
                .thenReturn(IUmsMemberFollowService.ApplyResult.ALREADY_FOLLOWED);

        AjaxResult result = controller.userFollowActoin(TARGET, "1");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals("关注成功", result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void unfollowIntentDelegatesFalseAndReportsUnfollowSuccess() {
        when(followService.applyFollowState(ME, 88L, Boolean.FALSE))
                .thenReturn(IUmsMemberFollowService.ApplyResult.UNFOLLOWED);

        AjaxResult result = controller.userFollowActoin(TARGET, "0");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals("取消关注成功", result.get(AjaxResult.MSG_TAG));
        verify(followService).applyFollowState(ME, 88L, Boolean.FALSE);
    }

    @Test
    void alreadyUnfollowedIsStillSuccess() {
        when(followService.applyFollowState(ME, 88L, Boolean.FALSE))
                .thenReturn(IUmsMemberFollowService.ApplyResult.ALREADY_UNFOLLOWED);

        AjaxResult result = controller.userFollowActoin(TARGET, "0");

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals("取消关注成功", result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void legacyCallerWithoutTargetStateDelegatesNullToggle() {
        when(followService.applyFollowState(eq(ME), eq(88L), any()))
                .thenReturn(IUmsMemberFollowService.ApplyResult.FOLLOWED);

        AjaxResult result = controller.userFollowActoin(TARGET);

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        assertEquals("关注成功", result.get(AjaxResult.MSG_TAG));
        verify(followService).applyFollowState(ME, 88L, null);
    }

    @Test
    void blankOrInvalidTargetStateFailsClosedWithoutDelegation() {
        AjaxResult blank = controller.userFollowActoin(TARGET, "");
        AjaxResult invalid = controller.userFollowActoin(TARGET, "toggle");

        assertEquals(500, blank.get(AjaxResult.CODE_TAG));
        assertEquals(500, invalid.get(AjaxResult.CODE_TAG));
        verify(followService, never()).applyFollowState(any(), any(), any());
    }

    @Test
    void unauthenticatedCallerIsRejectedBeforeDelegation() {
        StubController anonymous = new StubController(null);
        ReflectionTestUtils.setField(anonymous, "umsMemberFollowService", followService);

        AjaxResult result = anonymous.userFollowActoin(TARGET, "1");

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(followService, never()).applyFollowState(any(), any(), any());
    }

    @Test
    void invalidTargetIdIsRejectedBeforeDelegation() {
        AjaxResult result = controller.userFollowActoin("0", "1");

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        verify(followService, never()).applyFollowState(any(), any(), any());
    }

    @Test
    void selfFollowIsReportedAsSelfFollowNotMissingUser() {
        when(followService.applyFollowState(ME, 88L, Boolean.TRUE))
                .thenReturn(IUmsMemberFollowService.ApplyResult.SELF_FOLLOW);

        AjaxResult result = controller.userFollowActoin(TARGET, "1");

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("不能关注自己", result.get(AjaxResult.MSG_TAG));
    }

    @Test
    void serviceFailuresMapToErrorNotFakeSuccess() {
        when(followService.applyFollowState(ME, 88L, Boolean.TRUE))
                .thenReturn(IUmsMemberFollowService.ApplyResult.INVALID_MEMBER);
        assertEquals(500, controller.userFollowActoin(TARGET, "1").get(AjaxResult.CODE_TAG));

        when(followService.applyFollowState(ME, 88L, Boolean.TRUE))
                .thenReturn(IUmsMemberFollowService.ApplyResult.FAILED);
        assertEquals(500, controller.userFollowActoin(TARGET, "1").get(AjaxResult.CODE_TAG));
    }
}
