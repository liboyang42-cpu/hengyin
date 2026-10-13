package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.ConsentRecord;
import com.chengyinhub.business.domain.dto.RegistrationRequest;
import com.chengyinhub.business.mapper.ConsentRecordMapper;
import com.chengyinhub.business.service.IRegistrationCheckoutService;
import com.chengyinhub.business.service.RegistrationCheckoutResult;
import com.chengyinhub.business.service.impl.ConsentRecordServiceImpl;
import com.chengyinhub.common.config.ComplianceProperties;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RegistrationConsentGateTest {

    private static final String DOC = "activity_host_data_sharing";
    private static final String SCENE = "activity_signup";
    private static final String VERSION = "v2.1";

    private ApiRegistrationController controller;
    private IRegistrationCheckoutService checkoutService;
    private ConsentRecordMapper consentMapper;
    private RegistrationRequest request;

    @BeforeEach
    void setUp() {
        controller = spy(new ApiRegistrationController());
        doReturn(7L).when(controller).getAppUserId();
        checkoutService = mock(IRegistrationCheckoutService.class);
        consentMapper = mock(ConsentRecordMapper.class);
        ConsentRecordServiceImpl consentService = new ConsentRecordServiceImpl();
        ReflectionTestUtils.setField(consentService, "consentRecordMapper", consentMapper);
        ComplianceProperties properties = new ComplianceProperties();
        Map<String, String> versions = new HashMap<>();
        versions.put(DOC, VERSION);
        properties.setDocVersions(versions);
        ReflectionTestUtils.setField(controller, "registrationCheckoutService", checkoutService);
        ReflectionTestUtils.setField(controller, "consentRecordService", consentService);
        ReflectionTestUtils.setField(controller, "complianceProperties", properties);
        request = new RegistrationRequest();
        request.setOwnerType(2);
        request.setOwnerId(42L);
        request.setRealName("报名人");
        request.setPhone("13800138000");
        when(checkoutService.createOrReplay(7L, request)).thenReturn(
                new RegistrationCheckoutResult("下单成功", Collections.<String, Object>emptyMap()));
        when(checkoutService.retryPayment(7L, 99L)).thenReturn(
                new RegistrationCheckoutResult("支付参数已生成", Collections.<String, Object>emptyMap()));
    }

    @Test
    void topicTicketLimitReturnsReadableMessageAfterCurrentConsent() {
        when(consentMapper.selectLatest(7L, DOC, SCENE)).thenReturn(consent("AGREE", VERSION));
        when(checkoutService.createOrReplay(7L, request))
                .thenThrow(new IllegalStateException("SHOP_DAY_ACCOUNT_TICKET_LIMIT_REACHED"));

        AjaxResult result = controller.createRegistration(request);

        assertEquals(500, result.get("code"));
        assertEquals("已达到该主题的购票上限", result.get("msg"));
        verify(checkoutService).createOrReplay(7L, request);
    }

    @Test
    void missingConsentBlocksOrderCreationBeforeCheckout() {
        when(consentMapper.selectLatest(7L, DOC, SCENE)).thenReturn(null);

        AjaxResult result = controller.createRegistration(request);

        assertEquals("请先完成报名信息提供的单独同意", result.get("msg"));
        verify(checkoutService, never()).createOrReplay(any(), any());
    }

    @Test
    void revokedConsentBlocksRetryPaymentBeforeWechatParameters() {
        when(consentMapper.selectLatest(7L, DOC, SCENE)).thenReturn(consent("REVOKE", VERSION));

        AjaxResult result = controller.pay("99");

        assertEquals("请先完成报名信息提供的单独同意", result.get("msg"));
        verify(checkoutService, never()).retryPayment(any(), any());
    }

    @Test
    void consentRecordReadFailureBlocksOrderInsteadOfFailingOpen() {
        when(consentMapper.selectLatest(7L, DOC, SCENE))
                .thenThrow(new RuntimeException("CONSENT_STORE_UNAVAILABLE"));

        AjaxResult result = controller.createRegistration(request);

        assertEquals("请先完成报名信息提供的单独同意", result.get("msg"));
        verify(checkoutService, never()).createOrReplay(any(), any());
    }

    @Test
    void currentAgreementAllowsCheckout() {
        when(consentMapper.selectLatest(7L, DOC, SCENE)).thenReturn(consent("AGREE", VERSION));

        AjaxResult result = controller.createRegistration(request);

        assertEquals("下单成功", result.get("msg"));
        verify(consentMapper).selectLatest(7L, DOC, SCENE);
        verify(checkoutService).createOrReplay(7L, request);
    }

    private ConsentRecord consent(String eventType, String version) {
        ConsentRecord record = new ConsentRecord();
        record.setMemberId(7L);
        record.setDocType(DOC);
        record.setScene(SCENE);
        record.setDocVersion(version);
        record.setEventType(eventType);
        return record;
    }
}
