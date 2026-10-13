package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.service.IMemberInvitationService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiUmsMemberControllerInvitationSecurityTest {

    private IUmsMemberService memberService;
    private IMemberInvitationService invitationService;
    private TestableApiUmsMemberController controller;

    @BeforeEach
    void setUp() {
        memberService = mock(IUmsMemberService.class);
        invitationService = mock(IMemberInvitationService.class);
        controller = new TestableApiUmsMemberController(1001L);
        ReflectionTestUtils.setField(controller, "umsMemberService", memberService);
        ReflectionTestUtils.setField(controller, "memberInvitationService", invitationService);
    }

    @Test
    void selfInvitationFailsWithoutWriting() {
        when(invitationService.bindInviter(1001L, "1001")).thenReturn(false);

        AjaxResult result = controller.setInviter("1001");

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("邀请关系设置失败", result.get(AjaxResult.MSG_TAG));
        verify(memberService, never()).updateUmsMember(any(UmsMember.class));
    }

    @Test
    void nonexistentInviterFailsWithoutWriting() {
        when(invitationService.bindInviter(1001L, "9999")).thenReturn(false);

        AjaxResult result = controller.setInviter("9999");

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("邀请关系设置失败", result.get(AjaxResult.MSG_TAG));
        verify(memberService, never()).updateUmsMember(any(UmsMember.class));
    }

    @Test
    void malformedInviterUsesSameNonEnumeratingFailure() {
        when(invitationService.bindInviter(1001L, "not-a-number")).thenReturn(false);
        AjaxResult result = controller.setInviter("not-a-number");

        assertEquals(500, result.get(AjaxResult.CODE_TAG));
        assertEquals("邀请关系设置失败", result.get(AjaxResult.MSG_TAG));
        verify(memberService, never()).updateUmsMember(any(UmsMember.class));
    }

    private static class TestableApiUmsMemberController extends ApiUmsMemberController {
        private final Long appUserId;

        private TestableApiUmsMemberController(Long appUserId) {
            this.appUserId = appUserId;
        }

        @Override
        public Long getAppUserId() {
            return appUserId;
        }
    }
}
