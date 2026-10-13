package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.ConsentRecord;
import com.chengyinhub.business.domain.UmsMember;
import com.chengyinhub.business.domain.vo.DeregistrationStatusVO;
import com.chengyinhub.business.service.IAccountDeregistrationService;
import com.chengyinhub.business.service.IConsentRecordService;
import com.chengyinhub.business.service.IUmsMemberService;
import com.chengyinhub.common.config.ComplianceProperties;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.framework.web.service.SmsVerificationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiAccountDeregistrationControllerTest {

    @Test
    void applyRejectsWhenCancellationConsentIsMissing() {
        Fixture fixture = new Fixture(null);

        AjaxResult result = fixture.apply();

        assertEquals(HttpStatus.ERROR, result.get(AjaxResult.CODE_TAG));
        verify(fixture.deregistrationService, never()).apply(1001L);
    }

    @Test
    void applyRejectsWhenCancellationConsentWasRevoked() {
        Fixture fixture = new Fixture(consent("v2.0", "REVOKE"));

        AjaxResult result = fixture.apply();

        assertEquals(HttpStatus.ERROR, result.get(AjaxResult.CODE_TAG));
        verify(fixture.deregistrationService, never()).apply(1001L);
    }

    @Test
    void applyRejectsWhenCancellationConsentVersionIsStale() {
        Fixture fixture = new Fixture(consent("v1.0", "AGREE"));

        AjaxResult result = fixture.apply();

        assertEquals(HttpStatus.ERROR, result.get(AjaxResult.CODE_TAG));
        verify(fixture.deregistrationService, never()).apply(1001L);
    }

    @Test
    void applyContinuesWhenLatestCancellationConsentIsCurrentAndAgreed() {
        Fixture fixture = new Fixture(consent("v2.0", "AGREE"));
        DeregistrationStatusVO status = new DeregistrationStatusVO();
        status.setStatus("PENDING");
        when(fixture.deregistrationService.apply(1001L)).thenReturn(status);

        AjaxResult result = fixture.apply();

        assertEquals(HttpStatus.SUCCESS, result.get(AjaxResult.CODE_TAG));
        assertEquals(status, result.get(AjaxResult.DATA_TAG));
        verify(fixture.deregistrationService).apply(1001L);
        verify(fixture.smsVerificationService).verify(
                "13800138000", "123456", "198.51.100.20");
        verify(fixture.consentRecordService).latest(
                1001L, "account_cancellation_notice", "account_cancel");
        verify(fixture.consentRecordService, never()).record(
                anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void applyRejectsOtpThroughSharedAtomicVerifierWithExistingMessage() {
        Fixture fixture = new Fixture(consent("v2.0", "AGREE"));
        when(fixture.smsVerificationService.verify(
                "13800138000", "123456", "198.51.100.20")).thenReturn(false);

        AjaxResult result = fixture.apply();

        assertEquals(HttpStatus.ERROR, result.get(AjaxResult.CODE_TAG));
        assertEquals("验证码输入有误", result.get(AjaxResult.MSG_TAG));
        verify(fixture.smsVerificationService).verify(
                "13800138000", "123456", "198.51.100.20");
        verify(fixture.deregistrationService, never()).apply(1001L);
    }

    private static ConsentRecord consent(String version, String eventType) {
        ConsentRecord consent = new ConsentRecord();
        consent.setDocType("account_cancellation_notice");
        consent.setDocVersion(version);
        consent.setScene("account_cancel");
        consent.setEventType(eventType);
        return consent;
    }

    private static class Fixture {
        private final IAccountDeregistrationService deregistrationService =
                mock(IAccountDeregistrationService.class);
        private final IConsentRecordService consentRecordService = mock(IConsentRecordService.class);
        private final SmsVerificationService smsVerificationService = mock(SmsVerificationService.class);
        private final TestApiAccountDeregistrationController controller =
                new TestApiAccountDeregistrationController();

        private Fixture(ConsentRecord latestConsent) {
            IUmsMemberService memberService = mock(IUmsMemberService.class);
            UmsMember member = new UmsMember();
            member.setPhone("13800138000");
            when(memberService.selectUmsMemberById(1001L)).thenReturn(member);
            when(smsVerificationService.verify(
                    "13800138000", "123456", "198.51.100.20")).thenReturn(true);
            when(consentRecordService.latest(1001L, "account_cancellation_notice", "account_cancel"))
                    .thenReturn(latestConsent);
            ComplianceProperties complianceProperties = new ComplianceProperties();
            complianceProperties.setDocVersions(
                    Collections.singletonMap("account_cancellation_notice", "v2.0"));
            ReflectionTestUtils.setField(controller, "deregistrationService", deregistrationService);
            ReflectionTestUtils.setField(controller, "umsMemberService", memberService);
            ReflectionTestUtils.setField(controller, "smsVerificationService", smsVerificationService);
            ReflectionTestUtils.setField(controller, "consentRecordService", consentRecordService);
            ReflectionTestUtils.setField(controller, "complianceProperties", complianceProperties);
        }

        private AjaxResult apply() {
            HttpServletRequest request = mock(HttpServletRequest.class);
            when(request.getRemoteAddr()).thenReturn("198.51.100.20");
            when(request.getHeader("X-Real-IP")).thenReturn("203.0.113.7");
            return controller.apply(
                    "123456", "deregister-request-001", request);
        }
    }

    private static class TestApiAccountDeregistrationController
            extends ApiAccountDeregistrationController {
        @Override
        public Long getAppUserId() {
            return 1001L;
        }
    }
}
