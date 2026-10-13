package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.service.IRegistrationCheckoutService;
import com.chengyinhub.business.service.IConsentRecordService;
import com.chengyinhub.business.service.RegistrationCheckoutException;
import com.chengyinhub.business.domain.dto.RegistrationRequest;
import com.chengyinhub.common.config.ComplianceProperties;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Map;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

class ApiPaymentReadinessControllerTest {

    @Test
    void readinessEndpointReportsUnavailableWithoutExposingConfiguration() {
        IRegistrationCheckoutService checkoutService = mock(IRegistrationCheckoutService.class);
        when(checkoutService.isPaymentReady()).thenReturn(false);
        ApiRegistrationController controller = new ApiRegistrationController();
        ReflectionTestUtils.setField(controller, "registrationCheckoutService", checkoutService);

        AjaxResult result = controller.paymentReadiness();

        assertEquals(200, result.get(AjaxResult.CODE_TAG));
        Map<?, ?> data = (Map<?, ?>) result.get(AjaxResult.DATA_TAG);
        assertEquals(false, data.get("ready"));
        assertEquals("支付服务暂不可用", data.get("message"));
    }

    @Test
    void unavailablePaymentDoesNotCreatePaidSelfPlayPass() {
        IRegistrationCheckoutService checkoutService = mock(IRegistrationCheckoutService.class);
        IConsentRecordService consentRecordService = mock(IConsentRecordService.class);
        ApiRegistrationController controller = new LoggedInRegistrationController();
        ReflectionTestUtils.setField(controller, "registrationCheckoutService", checkoutService);
        ReflectionTestUtils.setField(controller, "consentRecordService", consentRecordService);
        ComplianceProperties complianceProperties = new ComplianceProperties();
        Map<String, String> docVersions = new HashMap<>();
        docVersions.put("activity_host_data_sharing", "v2.1");
        complianceProperties.setDocVersions(docVersions);
        ReflectionTestUtils.setField(controller, "complianceProperties", complianceProperties);
        RegistrationRequest request = new RegistrationRequest();
        request.setOwnerType(1);
        request.setOwnerId(9L);
        request.setRealName("测试用户");
        request.setPhone("13800138000");

        when(checkoutService.createOrReplay(7L, request))
                .thenThrow(new RegistrationCheckoutException(503, "支付服务暂不可用，请稍后再试"));
        when(consentRecordService.hasCurrentAgreement(7L, "activity_host_data_sharing", "activity_signup", "v2.1"))
                .thenReturn(true);

        AjaxResult result = controller.createRegistration(request);

        assertEquals(503, result.get(AjaxResult.CODE_TAG));
        verify(checkoutService).createOrReplay(7L, request);
    }

    private static class LoggedInRegistrationController extends ApiRegistrationController {
        @Override
        public Long getAppUserId() {
            return 7L;
        }
    }
}
