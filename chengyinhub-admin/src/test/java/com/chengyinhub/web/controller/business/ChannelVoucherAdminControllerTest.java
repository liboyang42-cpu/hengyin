package com.chengyinhub.web.controller.business;

import com.chengyinhub.business.domain.dto.ChannelAccountRequest;
import com.chengyinhub.business.domain.dto.ChannelOrderVerificationRequest;
import com.chengyinhub.business.domain.dto.ChannelVoucherIssueRequest;
import com.chengyinhub.business.exception.ChannelVoucherException;
import com.chengyinhub.business.service.IChannelOrderVerificationService;
import com.chengyinhub.business.service.IChannelRedemptionService;
import com.chengyinhub.business.service.IChannelVoucherService;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.prepost.PreAuthorize;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

/** 管理端渠道接口：真实身份、权限注解、无权限不执行。 */
class ChannelVoucherAdminControllerTest {

    private IChannelOrderVerificationService verificationService;
    private IChannelVoucherService voucherService;
    private IChannelRedemptionService redemptionService;
    private ChannelVoucherAdminController controller;

    @BeforeEach
    void setUp() {
        verificationService = mock(IChannelOrderVerificationService.class);
        voucherService = mock(IChannelVoucherService.class);
        redemptionService = mock(IChannelRedemptionService.class);
        controller = spy(new ChannelVoucherAdminController(verificationService, voucherService, redemptionService));
    }

    @Test
    void missingAdminIdentityIsRejectedBeforeServiceCall() {
        doReturn(null).when(controller).getUserId();

        assertThrows(ChannelVoucherException.class,
                () -> controller.issue(new ChannelVoucherIssueRequest()));
        verify(voucherService, never()).issue(anyLong(), any());
    }

    @Test
    void authenticatedAdminReachesService() {
        doReturn(9L).when(controller).getUserId();

        controller.issue(new ChannelVoucherIssueRequest());
        verify(voucherService).issue(eq(9L), any());

        controller.verify(new ChannelOrderVerificationRequest());
        verify(verificationService).verifyByReference(eq(9L), any());

        controller.registerAccount(new ChannelAccountRequest());
        verify(verificationService).registerAccount(eq(9L), any());
    }

    @Test
    void rejectionKeepsReasonCodeAndStatus() {
        AjaxResult rejected = controller.handleRejection(
                ChannelVoucherException.conflict("VOUCHER_ALREADY_ISSUED", "已发行"));

        assertEquals(409, rejected.get(AjaxResult.CODE_TAG));
        assertEquals("VOUCHER_ALREADY_ISSUED", rejected.get("reasonCode"));
    }


    @Test
    void issuanceResponseIsExcludedFromAuditStorage() throws Exception {
        com.chengyinhub.common.annotation.Log audit = ChannelVoucherAdminController.class
                .getMethod("issue", ChannelVoucherIssueRequest.class).getAnnotation(com.chengyinhub.common.annotation.Log.class);
        org.junit.jupiter.api.Assertions.assertFalse(audit.isSaveResponseData());
    }

    @Test
    void everyWriteEndpointDeclaresANewChannelPermission() {
        for (Method method : ChannelVoucherAdminController.class.getDeclaredMethods()) {
            PreAuthorize annotation = method.getAnnotation(PreAuthorize.class);
            if (annotation == null) {
                continue;
            }
            assertTrue(annotation.value().contains("business:marketing:channel:"),
                    method.getName() + " 必须声明渠道权限串");
        }
    }
}
