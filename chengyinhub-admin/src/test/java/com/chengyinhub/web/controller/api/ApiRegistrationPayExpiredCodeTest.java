package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.ConsentRecord;
import com.chengyinhub.business.mapper.ConsentRecordMapper;
import com.chengyinhub.business.service.IRegistrationCheckoutService;
import com.chengyinhub.business.service.RegistrationOrderExpiredException;
import com.chengyinhub.common.config.ComplianceProperties;
import com.chengyinhub.common.core.domain.AjaxResult;
import com.chengyinhub.business.service.impl.ConsentRecordServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 2026-09-17 拍板 #21:待支付单过期被 /pay 拒时,必须给前端一个机器可识别的业务码(410)。
 *
 * <p>旧行为:RegistrationOrderExpiredException 落进兜底 catch,变成 code=500 + 文案,
 * 前端只能靠匹配「订单已过期」四个字,无法可靠区分「过期(可自动换键重建)」与其它失败。
 * 本测试钉住 410 与原文案两端对齐(前端 checkout-workflow.isOrderExpiredFailure 按此判)。
 */
class ApiRegistrationPayExpiredCodeTest {

    private static final String DOC = "activity_host_data_sharing";
    private static final String SCENE = "activity_signup";
    private static final String VERSION = "v2.1";
    private static final String EXPIRED_MESSAGE = "订单已过期,请重新报名";

    private ApiRegistrationController controller;
    private IRegistrationCheckoutService checkoutService;
    private ConsentRecordMapper consentMapper;

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
        when(consentMapper.selectLatest(7L, DOC, SCENE)).thenReturn(agreedConsent());
    }

    @Test
    void expiredOrderOnJsapiPayIsMappedToMachineReadable410() {
        when(checkoutService.retryPayment(7L, 99L))
                .thenThrow(new RegistrationOrderExpiredException(EXPIRED_MESSAGE));

        AjaxResult result = controller.pay("99");

        assertEquals(410, result.get("code"),
                "过期单必须给 410,前端才能自动换幂等键重建而不是只弹文案");
        assertEquals(EXPIRED_MESSAGE, result.get("msg"),
                "文案与 CmsRegistrationServiceImpl 的拒绝原文两端对齐");
        verify(checkoutService).retryPayment(7L, 99L);
    }

    @Test
    void expiredOrderOnAppPayKeepsTheSameContract() {
        when(checkoutService.retryPayment(7L, 99L, true))
                .thenThrow(new RegistrationOrderExpiredException(EXPIRED_MESSAGE));

        AjaxResult result = controller.payApp("99");

        assertEquals(410, result.get("code"), "App 支付通道与小程序同一口径");
        assertEquals(EXPIRED_MESSAGE, result.get("msg"));
        verify(checkoutService).retryPayment(7L, 99L, true);
    }

    private ConsentRecord agreedConsent() {
        ConsentRecord record = new ConsentRecord();
        record.setMemberId(7L);
        record.setDocType(DOC);
        record.setScene(SCENE);
        record.setDocVersion(VERSION);
        record.setEventType("AGREE");
        return record;
    }
}
