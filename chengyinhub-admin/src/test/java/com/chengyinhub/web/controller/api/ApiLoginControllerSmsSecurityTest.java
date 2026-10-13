package com.chengyinhub.web.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;

import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.framework.web.service.SmsVerificationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class ApiLoginControllerSmsSecurityTest
{
    @Test
    void failedPhoneLoginUsesTrustedPeerIpAndGenericOtpError()
    {
        ApiLoginController controller = new ApiLoginController();
        SmsVerificationService smsService = mock(SmsVerificationService.class);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("198.51.100.20");
        when(request.getHeader("X-Real-IP")).thenReturn("203.0.113.7");
        when(smsService.verify("13800138000", "000000", "198.51.100.20")).thenReturn(false);
        ReflectionTestUtils.setField(controller, "smsVerificationService", smsService);

        AjaxResult result = controller.loginPhone("13800138000", "000000", request);

        assertEquals("验证码错误或已过期", result.get(AjaxResult.MSG_TAG));
        verify(smsService).verify("13800138000", "000000", "198.51.100.20");
    }

    @Test
    void registerDelegatesVerificationAndPreservesItsExistingErrorMessage()
    {
        Fixture fixture = new Fixture();

        AjaxResult result = fixture.controller.register(
                "张三", "13800138000", "000000", "password", "wx-code", fixture.request);

        assertEquals("验证码输入有误", result.get(AjaxResult.MSG_TAG));
        verify(fixture.smsService).verify("13800138000", "000000", "198.51.100.20");
    }

    @Test
    void registerRejectsMissingParametersBeforeConsumingAttempt()
    {
        Fixture fixture = new Fixture();

        AjaxResult result = fixture.controller.register(
                "张三", "13800138000", "000000", "", "wx-code", fixture.request);

        assertEquals("参数有误", result.get(AjaxResult.MSG_TAG));
        verify(fixture.smsService, never()).verify("13800138000", "000000", "198.51.100.20");
    }

    @Test
    void findPasswordDelegatesVerificationAndPreservesItsExistingErrorMessage()
    {
        Fixture fixture = new Fixture();

        AjaxResult result = fixture.controller.findpassword(
                "13800138000", "000000", "new-password", fixture.request);

        assertEquals("验证码输入有误", result.get(AjaxResult.MSG_TAG));
        verify(fixture.smsService).verify("13800138000", "000000", "198.51.100.20");
    }

    @Test
    void findPasswordRejectsMissingParametersBeforeConsumingAttempt()
    {
        Fixture fixture = new Fixture();

        AjaxResult result = fixture.controller.findpassword(
                "13800138000", "000000", "", fixture.request);

        assertEquals("参数有误", result.get(AjaxResult.MSG_TAG));
        verify(fixture.smsService, never()).verify("13800138000", "000000", "198.51.100.20");
    }

    private static final class Fixture
    {
        private final ApiLoginController controller = new ApiLoginController();
        private final SmsVerificationService smsService = mock(SmsVerificationService.class);
        private final HttpServletRequest request = mock(HttpServletRequest.class);

        private Fixture()
        {
            when(request.getRemoteAddr()).thenReturn("198.51.100.20");
            when(request.getHeader("X-Real-IP")).thenReturn("203.0.113.7");
            ReflectionTestUtils.setField(controller, "smsVerificationService", smsService);
        }
    }
}
