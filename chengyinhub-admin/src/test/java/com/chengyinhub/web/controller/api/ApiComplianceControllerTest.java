package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.ConsentRecord;
import com.chengyinhub.business.service.IConsentRecordService;
import com.chengyinhub.common.config.ComplianceProperties;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiComplianceControllerTest {

    private static ComplianceProperties complianceProps() {
        ComplianceProperties props = new ComplianceProperties();
        Map<String, String> versions = new HashMap<>();
        versions.put("privacy_policy", "v2.1");
        versions.put("activity_host_data_sharing", "v2.1");
        versions.put("merchant_onsite_data_sharing", "v1.0");
        versions.put("bank_account_collection", "v1.0");
        props.setDocVersions(versions);
        return props;
    }

    /**
     * 提现单独同意的 docType/scene 必须与小程序 utils/withdraw-form.js 里的
     * CONSENT_DOC_TYPE / CONSENT_SCENE 逐字一致。
     *
     * ★这条不是形式主义:两边一旦对不上,isAllowedScene 返回 false ⇒ 接口回
     * 「协议类型或场景不合法」⇒ 小程序侧 .catch 只弹一句「同意记录未保存,请检查
     * 网络后重试」,而真正的提交(onTxSave/saveData/submitForm)压根不会执行。
     * 三个提现入口同时静默失效,现场看起来像网络问题,查不到真因。
     */
    @Test
    void withdrawalConsentDocTypeIsWhitelisted() {
        IConsentRecordService service = mock(IConsentRecordService.class);
        TestApiComplianceController controller = new TestApiComplianceController();
        ReflectionTestUtils.setField(controller, "consentRecordService", service);
        ReflectionTestUtils.setField(controller, "complianceProperties", complianceProps());
        Map<String, Object> body = new HashMap<>();
        // 这两个字面量必须跟 chengyinhub-xcx/utils/withdraw-form.js 对齐,别改单边
        body.put("docType", "bank_account_collection");
        body.put("scene", "withdrawal");
        body.put("eventType", "AGREE");
        body.put("requestId", "consent-request-withdraw-001");

        AjaxResult result = controller.record(body, mock(HttpServletRequest.class));

        assertEquals(HttpStatus.SUCCESS, result.get(AjaxResult.CODE_TAG),
                "提现单独同意被拒 ⇒ 三个提现入口都会卡在「同意记录未保存」且永不提交");
        verify(service).record(eq(1001L), eq("bank_account_collection"), eq("v1.0"),
                eq("withdrawal"), eq("AGREE"), eq("consent-request-withdraw-001"), anyString());
    }

    /** 负控:场景对不上时必须拒,别让白名单退化成「只要 docType 认识就放行」。 */
    @Test
    void withdrawalConsentRejectsForeignScene() {
        IConsentRecordService service = mock(IConsentRecordService.class);
        TestApiComplianceController controller = new TestApiComplianceController();
        ReflectionTestUtils.setField(controller, "consentRecordService", service);
        ReflectionTestUtils.setField(controller, "complianceProperties", complianceProps());
        Map<String, Object> body = new HashMap<>();
        body.put("docType", "bank_account_collection");
        body.put("scene", "app_launch");
        body.put("eventType", "AGREE");
        body.put("requestId", "consent-request-withdraw-002");

        AjaxResult result = controller.record(body, mock(HttpServletRequest.class));

        assertEquals(HttpStatus.ERROR, result.get(AjaxResult.CODE_TAG));
        verify(service, never()).record(anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    void recordUsesServerControlledDocumentVersion() {
        IConsentRecordService service = mock(IConsentRecordService.class);
        TestApiComplianceController controller = new TestApiComplianceController();
        ReflectionTestUtils.setField(controller, "consentRecordService", service);
        ReflectionTestUtils.setField(controller, "complianceProperties", complianceProps());
        Map<String, Object> body = new HashMap<>();
        body.put("docType", "privacy_policy");
        body.put("scene", "app_launch");
        body.put("eventType", "AGREE");
        body.put("requestId", "consent-request-api-001");

        AjaxResult result = controller.record(body, mock(HttpServletRequest.class));

        assertEquals(HttpStatus.SUCCESS, result.get(AjaxResult.CODE_TAG));
        verify(service).record(eq(1001L), eq("privacy_policy"), eq("v2.1"), eq("app_launch"),
                eq("AGREE"), eq("consent-request-api-001"), anyString());
    }

    @Test
    void recordRejectsUnknownDocumentWithoutWritingEvent() {
        IConsentRecordService service = mock(IConsentRecordService.class);
        TestApiComplianceController controller = new TestApiComplianceController();
        ReflectionTestUtils.setField(controller, "consentRecordService", service);
        ReflectionTestUtils.setField(controller, "complianceProperties", complianceProps());
        Map<String, Object> body = new HashMap<>();
        body.put("docType", "client_invented_document");
        body.put("scene", "app_launch");
        body.put("eventType", "AGREE");
        body.put("requestId", "consent-request-api-002");

        AjaxResult result = controller.record(body, mock(HttpServletRequest.class));

        assertEquals(HttpStatus.ERROR, result.get(AjaxResult.CODE_TAG));
        verify(service, never()).record(eq(1001L), anyString(), anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void recordAcceptsActivityHostDataSharingConsent() {
        IConsentRecordService service = mock(IConsentRecordService.class);
        TestApiComplianceController controller = new TestApiComplianceController();
        ReflectionTestUtils.setField(controller, "consentRecordService", service);
        ReflectionTestUtils.setField(controller, "complianceProperties", complianceProps());
        Map<String, Object> body = new HashMap<>();
        body.put("docType", "activity_host_data_sharing");
        body.put("scene", "activity_signup");
        body.put("eventType", "AGREE");
        body.put("requestId", "consent-request-api-003");

        AjaxResult result = controller.record(body, mock(HttpServletRequest.class));

        assertEquals(HttpStatus.SUCCESS, result.get(AjaxResult.CODE_TAG));
        verify(service).record(eq(1001L), eq("activity_host_data_sharing"), eq("v2.1"),
                eq("activity_signup"), eq("AGREE"), eq("consent-request-api-003"), anyString());
    }

    @Test
    void merchantConsentWritesOneScopeAndReturnsDatabaseReadback() {
        IConsentRecordService service = mock(IConsentRecordService.class);
        ConsentRecord readback = new ConsentRecord();
        readback.setDocType("merchant_onsite_data_sharing");
        readback.setDocVersion("v1.0");
        readback.setScene("merchant_redeem");
        readback.setScopeType("MERCHANT");
        readback.setScopeId(2001L);
        readback.setEventType("AGREE");
        when(service.latest(1001L, "merchant_onsite_data_sharing", "merchant_redeem",
                "MERCHANT", 2001L)).thenReturn(readback);
        TestApiComplianceController controller = new TestApiComplianceController();
        ReflectionTestUtils.setField(controller, "consentRecordService", service);
        ReflectionTestUtils.setField(controller, "complianceProperties", complianceProps());
        Map<String, Object> body = new HashMap<>();
        body.put("docType", "merchant_onsite_data_sharing");
        body.put("scene", "merchant_redeem");
        body.put("scopeType", "MERCHANT");
        body.put("scopeId", 2001L);
        body.put("eventType", "AGREE");
        body.put("requestId", "merchant-consent-api-001");

        AjaxResult result = controller.record(body, mock(HttpServletRequest.class));

        assertEquals(HttpStatus.SUCCESS, result.get(AjaxResult.CODE_TAG));
        verify(service).record(eq(1001L), eq("merchant_onsite_data_sharing"), eq("v1.0"),
                eq("merchant_redeem"), eq("MERCHANT"), eq(2001L), eq("AGREE"),
                eq("merchant-consent-api-001"), anyString());
        Map<?, ?> data = (Map<?, ?>) result.get(AjaxResult.DATA_TAG);
        assertEquals(2001L, data.get("scopeId"));
        assertEquals("AGREE", data.get("eventType"));
    }

    @Test
    void merchantConsentWithoutExactScopeFailsClosed() {
        IConsentRecordService service = mock(IConsentRecordService.class);
        TestApiComplianceController controller = new TestApiComplianceController();
        ReflectionTestUtils.setField(controller, "consentRecordService", service);
        ReflectionTestUtils.setField(controller, "complianceProperties", complianceProps());
        Map<String, Object> body = new HashMap<>();
        body.put("docType", "merchant_onsite_data_sharing");
        body.put("scene", "merchant_redeem");
        body.put("scopeType", "MERCHANT");
        body.put("eventType", "AGREE");
        body.put("requestId", "merchant-consent-api-002");

        AjaxResult result = controller.record(body, mock(HttpServletRequest.class));

        assertEquals(HttpStatus.ERROR, result.get(AjaxResult.CODE_TAG));
        verify(service, never()).record(eq(1001L), anyString(), anyString(), anyString(),
                anyString(), eq(null), anyString(), anyString(), anyString());
    }

    private static class TestApiComplianceController extends ApiComplianceController {
        @Override
        public Long getAppUserId() {
            return 1001L;
        }
    }
}
