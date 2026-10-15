package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.access.merchant.IMerchantAccessService;
import com.chengyinhub.business.access.merchant.MerchantAccessContext;
import com.chengyinhub.business.access.merchant.MerchantPermission;
import com.chengyinhub.business.access.merchant.MerchantRoleCode;
import com.chengyinhub.business.domain.MerchantChapterApplication;
import com.chengyinhub.business.service.IMerchantChapterApplicationService;
import com.chengyinhub.business.service.MerchantChapterApplicationExistsException;
import com.chengyinhub.business.service.MerchantChapterApplicationWithdrawException;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** API 层只从登录态取申请人/审核人，不能信 body 里的伪造 memberId。 */
class ApiMerchantChapterApplicationControllerTest {

    private ApiMerchantChapterApplicationController controller;
    private IMerchantChapterApplicationService applicationService;
    private IMerchantAccessService merchantAccessService;

    @BeforeEach
    void setUp() {
        applicationService = mock(IMerchantChapterApplicationService.class);
        merchantAccessService = mock(IMerchantAccessService.class);
        controller = new ApiMerchantChapterApplicationController();
        ReflectionTestUtils.setField(controller, "merchantChapterApplicationService", applicationService);
        ReflectionTestUtils.setField(controller, "merchantAccessService", merchantAccessService);
        ReflectionTestUtils.setField(controller, "wxContentSecurityService",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.IWxContentSecurityService.class));
        ReflectionTestUtils.setField(controller, "umsMemberService",
                org.mockito.Mockito.mock(com.chengyinhub.business.service.IUmsMemberService.class));
        when(merchantAccessService.require(2001L, MerchantPermission.COOP_MANAGE)).thenReturn(
                new MerchantAccessContext(2001L, 77L, 2000L, MerchantRoleCode.MERCHANT_MANAGER));
    }

    private ApiMerchantChapterApplicationController loggedInAs(long memberId) {
        ApiMerchantChapterApplicationController spy = spy(controller);
        doReturn(memberId).when(spy).getAppUserId();
        return spy;
    }

    @Test
    void ownerMineIgnoresBodyIdentityAndUsesExistingMerchantScope() {
        ApiMerchantChapterApplicationController c = loggedInAs(2001L);
        Map<String, Object> body = new HashMap<>();
        body.put("publisherMemberId", 9999L);
        when(applicationService.selectForPublisher(2001L)).thenReturn(java.util.Collections.emptyList());
        assertEquals(200, c.ownerMine(body).get("code"));
        verify(applicationService).selectForPublisher(2001L);

        body.put("scope", "MERCHANT");
        when(merchantAccessService.require(2001L, MerchantPermission.PROJECT_MANAGE)).thenReturn(
                new MerchantAccessContext(2001L, 77L, 2000L, MerchantRoleCode.MERCHANT_MANAGER));
        when(applicationService.selectForPublisher(2000L)).thenReturn(java.util.Collections.emptyList());
        assertEquals(200, c.ownerMine(body).get("code"));
        verify(applicationService).selectForPublisher(2000L);
    }

    @Test
    void apply_usesAuthenticatedMemberInsteadOfBodyIdentity() {
        ApiMerchantChapterApplicationController c = loggedInAs(2001L);
        MerchantChapterApplication application = new MerchantChapterApplication();
        application.setId(9L);
        when(applicationService.apply(73L, 2000L, "愿意承接")).thenReturn(application);
        Map<String, Object> body = new HashMap<>();
        body.put("chapterId", 73L);
        body.put("message", "愿意承接");
        body.put("merchantId", 9999L); // 伪造字段不得影响服务调用

        AjaxResult result = c.apply(body);

        assertEquals(200, result.get("code"));
        verify(applicationService).apply(73L, 2000L, "愿意承接");
    }

    @Test
    void apply_existingApplicationExposesStableErrorCode() {
        ApiMerchantChapterApplicationController c = loggedInAs(2001L);
        when(applicationService.apply(eq(73L), eq(2000L), eq("再次提交")))
                .thenThrow(new MerchantChapterApplicationExistsException("状态已存在"));
        Map<String, Object> body = new HashMap<>();
        body.put("chapterId", 73L);
        body.put("message", "再次提交");

        AjaxResult result = c.apply(body);

        assertEquals(500, result.get("code"));
        assertEquals(MerchantChapterApplicationExistsException.ERROR_CODE, result.get("errorCode"));
        assertEquals("状态已存在", result.get("msg"));
    }

    @Test
    void withdraw_usesAuthenticatedMerchantAndExposesStableErrorCode() {
        ApiMerchantChapterApplicationController c = loggedInAs(2001L);
        when(applicationService.withdraw(91L, 2000L)).thenThrow(
                new MerchantChapterApplicationWithdrawException(
                        MerchantChapterApplicationWithdrawException.ERROR_CODE_FINALIZED,
                        "已通过或已拒绝的申请不可撤回"));
        Map<String, Object> body = new HashMap<>();
        body.put("applicationId", 91L);
        body.put("merchantId", 9999L);

        AjaxResult result = c.withdraw(body);

        assertEquals(500, result.get("code"));
        assertEquals(MerchantChapterApplicationWithdrawException.ERROR_CODE_FINALIZED, result.get("errorCode"));
        verify(applicationService).withdraw(91L, 2000L);
    }

    @Test
    void audit_usesAuthenticatedPublisherInsteadOfBodyIdentity() {
        ApiMerchantChapterApplicationController c = loggedInAs(1001L);
        MerchantChapterApplication application = new MerchantChapterApplication();
        application.setId(91L);
        when(applicationService.audit(91L, 1001L, true, "同意")).thenReturn(application);
        Map<String, Object> body = new HashMap<>();
        body.put("applicationId", 91L);
        body.put("approved", true);
        body.put("auditRemark", "同意");
        body.put("publisherMemberId", 9999L);

        AjaxResult result = c.audit(body);

        assertEquals(200, result.get("code"));
        verify(applicationService).audit(91L, 1001L, true, "同意");
    }

    @Test
    void merchantManagerHostActionsUseOwnerOnlyWhenScopeIsExplicit() {
        ApiMerchantChapterApplicationController c = loggedInAs(1001L);
        when(merchantAccessService.require(1001L, MerchantPermission.PROJECT_MANAGE)).thenReturn(
                new MerchantAccessContext(1001L, 88L, 1000L, MerchantRoleCode.MERCHANT_MANAGER));
        MerchantChapterApplication application = new MerchantChapterApplication();
        application.setId(92L);
        when(applicationService.audit(92L, 1000L, true, "同意")).thenReturn(application);
        Map<String, Object> body = new HashMap<>();
        body.put("applicationId", 92L);
        body.put("approved", true);
        body.put("auditRemark", "同意");
        body.put("scope", "MERCHANT");

        AjaxResult result = c.audit(body);

        assertEquals(200, result.get("code"));
        verify(applicationService).audit(92L, 1000L, true, "同意");
    }
}
