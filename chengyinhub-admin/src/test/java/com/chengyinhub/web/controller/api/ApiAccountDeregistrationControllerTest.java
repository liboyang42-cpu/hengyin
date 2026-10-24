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
import com.chengyinhub.framework.web.service.WechatReconfirmationService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.any;
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

    @Test void unboundPhoneRequiresFreshWechatOwnerConfirmation() {
        Fixture f = new Fixture(consent("v2.0", "AGREE"));
        f.member.setPhone(null); f.member.setWxOpenId("owner-seven");
        when(f.wechat.verify(1001L, "owner-seven", "fresh", "198.51.100.20")).thenReturn(true);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("198.51.100.20");
        assertEquals(HttpStatus.SUCCESS, f.controller.apply(null, "fresh", "request-1", request).get(AjaxResult.CODE_TAG));
        verify(f.deregistrationService).apply(1001L);
        verifyNoSms(f);
    }
    // CL-29 / F-W1-09: a whitespace-only phone is "not bound" (precheck reports WECHAT for it),
    // so apply must take the WeChat confirmation path instead of an SMS check that can never pass.
    @Test void blankPhoneIsTreatedAsUnboundAndUsesWechatConfirmation() {
        Fixture f = new Fixture(consent("v2.0", "AGREE"));
        f.member.setPhone("   "); f.member.setWxOpenId("owner-seven");
        when(f.wechat.verify(1001L, "owner-seven", "fresh", "198.51.100.20")).thenReturn(true);
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("198.51.100.20");
        assertEquals(HttpStatus.SUCCESS, f.controller.apply(null, "fresh", "request-1", request).get(AjaxResult.CODE_TAG));
        verify(f.deregistrationService).apply(1001L);
        verifyNoSms(f);
    }
    @Test void mismatchedWechatAndMissingCodeDoNotSubmitAndBoundPhoneCannotBypassSms() {
        Fixture f = new Fixture(consent("v2.0", "AGREE"));
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("198.51.100.20");
        assertEquals(HttpStatus.ERROR, f.controller.apply(null, "fresh", "request-1", request).get(AjaxResult.CODE_TAG));
        verify(f.wechat, never()).verify(anyLong(), any(), any(), any());
        f.member.setPhone(null); f.member.setWxOpenId("owner-seven");
        assertEquals(HttpStatus.ERROR, f.controller.apply(null, "wrong", "request-1", request).get(AjaxResult.CODE_TAG));
        verify(f.deregistrationService, never()).apply(1001L);
    }
    @Test void retryAfterLostResponseReplaysExistingPendingWithoutConsumingAnotherCode() {
        Fixture f = new Fixture(consent("v2.0", "AGREE"));
        DeregistrationStatusVO pending = new DeregistrationStatusVO();
        pending.setStatus("PENDING");
        when(f.deregistrationService.status(1001L)).thenReturn(pending);
        when(f.smsVerificationService.verify("13800138000", "123456", "198.51.100.20")).thenReturn(false);

        AjaxResult result = f.apply();

        assertEquals(HttpStatus.SUCCESS, result.get(AjaxResult.CODE_TAG));
        assertEquals(pending, result.get(AjaxResult.DATA_TAG));
        verifyNoSms(f);
        verify(f.deregistrationService, never()).apply(1001L);
    }
    @Test void nonPendingStatusStillRequiresVerification() {
        Fixture f = new Fixture(consent("v2.0", "AGREE"));
        DeregistrationStatusVO normal = new DeregistrationStatusVO();
        normal.setStatus("CANCELLED");
        when(f.deregistrationService.status(1001L)).thenReturn(normal);
        when(f.smsVerificationService.verify("13800138000", "123456", "198.51.100.20")).thenReturn(false);

        AjaxResult result = f.apply();

        assertEquals(HttpStatus.ERROR, result.get(AjaxResult.CODE_TAG));
        verify(f.deregistrationService, never()).apply(1001L);
    }
    private static void verifyNoSms(Fixture f) { verify(f.smsVerificationService, never()).verify(any(), any(), any()); }

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
        private final WechatReconfirmationService wechat = mock(WechatReconfirmationService.class);
        private final UmsMember member = new UmsMember();
        private final TestApiAccountDeregistrationController controller =
                new TestApiAccountDeregistrationController();

        private Fixture(ConsentRecord latestConsent) {
            IUmsMemberService memberService = mock(IUmsMemberService.class);
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
            ReflectionTestUtils.setField(controller, "wechatReconfirmationService", wechat);
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
