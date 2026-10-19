package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.ConsentRecord;
import com.chengyinhub.business.service.IConsentRecordService;
import com.chengyinhub.common.config.ComplianceProperties;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.servlet.http.HttpServletRequest;
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
        versions.put("photo_model_processing", "v1.0");
        versions.put("voice_clone_processing", "v1.0");
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

        stubReadback(service, body);
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

        stubReadback(service, body);
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

        stubReadback(service, body);
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

        stubReadback(service, body);
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

        stubReadback(service, body);
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

        stubReadback(service, body);
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

        stubReadback(service, body);
        AjaxResult result = controller.record(body, mock(HttpServletRequest.class));

        assertEquals(HttpStatus.ERROR, result.get(AjaxResult.CODE_TAG));
        verify(service, never()).record(eq(1001L), anyString(), anyString(), anyString(),
                anyString(), eq(null), anyString(), anyString(), anyString());
    }

    private static void stubReadback(IConsentRecordService service, Map<String, Object> body) {
        ConsentRecord row = new ConsentRecord();
        row.setMemberId(1001L); row.setDocType((String)body.get("docType"));
        row.setDocVersion(complianceProps().versionOf(row.getDocType()));
        row.setScene((String)body.get("scene")); row.setEventType((String)body.get("eventType"));
        row.setRequestId((String)body.get("requestId")); row.setScopeType((String)body.get("scopeType"));
        row.setScopeId((Long)body.get("scopeId"));
        when(service.byRequestId(1001L, row.getRequestId())).thenReturn(row);
    }

    @Test void zeroRowsWithoutExactUuidReadbackNeverReportsAgreementRecorded() {
        IConsentRecordService service = mock(IConsentRecordService.class);
        TestApiComplianceController controller = new TestApiComplianceController();
        ReflectionTestUtils.setField(controller, "consentRecordService", service);
        ReflectionTestUtils.setField(controller, "complianceProperties", complianceProps());
        Map<String,Object> body = new HashMap<>();
        body.put("docType", "photo_model_processing"); body.put("scene", "object_photo");
        body.put("eventType", "AGREE"); body.put("requestId", "same-uuid");
        assertEquals(HttpStatus.ERROR, controller.record(body, mock(HttpServletRequest.class)).get(AjaxResult.CODE_TAG));
        stubReadback(service, body);
        assertEquals(HttpStatus.SUCCESS, controller.record(body, mock(HttpServletRequest.class)).get(AjaxResult.CODE_TAG));
        ConsentRecord collision = service.byRequestId(1001L, "same-uuid");
        collision.setEventType("REVOKE");
        assertEquals(HttpStatus.ERROR, controller.record(body, mock(HttpServletRequest.class)).get(AjaxResult.CODE_TAG));
        collision.setEventType("AGREE"); collision.setDocVersion("old");
        assertEquals(HttpStatus.ERROR, controller.record(body, mock(HttpServletRequest.class)).get(AjaxResult.CODE_TAG));
    }

    @Test
    void homeCityConsentWritesAndReadsBackItsOwnScene() {
        IConsentRecordService service = mock(IConsentRecordService.class);
        TestApiComplianceController controller = new TestApiComplianceController();
        ReflectionTestUtils.setField(controller, "consentRecordService", service);
        ReflectionTestUtils.setField(controller, "complianceProperties", complianceProps());
        var location = mock(com.chengyinhub.business.service.impl.RoamLocationConsentService.class);
        ReflectionTestUtils.setField(controller, "roamLocationConsentService", location);
        Map<String, Object> body = new HashMap<>(Map.of("docType", "privacy_policy",
                "scene", "home_city_location", "eventType", "AGREE", "requestId", "home-city-consent-001",
                "expectedRequestId", ""));
        stubReadback(service, body);
        ConsentRecord recorded = service.byRequestId(1001L, "home-city-consent-001");
        when(location.record(eq(1001L), eq("home_city_location"), eq("AGREE"),
                eq("home-city-consent-001"), eq(""), anyString())).thenReturn(recorded);
        AjaxResult result = controller.record(body, mock(HttpServletRequest.class));
        assertEquals(HttpStatus.SUCCESS, result.get(AjaxResult.CODE_TAG));
        Map<?, ?> data = (Map<?, ?>) result.get(AjaxResult.DATA_TAG);
        assertEquals("home_city_location", data.get("scene"));
        assertEquals("v2.1", data.get("docVersion"));
        assertEquals("home-city-consent-001", data.get("requestId"));
        verify(location).record(eq(1001L), eq("home_city_location"), eq("AGREE"),
                eq("home-city-consent-001"), eq(""), anyString());
        verify(service, never()).record(anyLong(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString());
        when(service.latest(1001L, "privacy_policy", "home_city_location")).thenReturn(recorded);
        AjaxResult latest = controller.latest(body);
        assertEquals(HttpStatus.SUCCESS, latest.get(AjaxResult.CODE_TAG));
        assertEquals("home_city_location", ((Map<?, ?>) latest.get(AjaxResult.DATA_TAG)).get("scene"));
    }

    @Test
    void homeCityConsentWithoutMatchingReadbackFailsClosed() {
        IConsentRecordService service = mock(IConsentRecordService.class);
        TestApiComplianceController controller = new TestApiComplianceController();
        ReflectionTestUtils.setField(controller, "consentRecordService", service);
        ReflectionTestUtils.setField(controller, "complianceProperties", complianceProps());
        var location = mock(com.chengyinhub.business.service.impl.RoamLocationConsentService.class);
        ReflectionTestUtils.setField(controller, "roamLocationConsentService", location);
        Map<String, Object> body = new HashMap<>(Map.of("docType", "privacy_policy",
                "scene", "home_city_location", "eventType", "AGREE", "requestId", "home-city-consent-002",
                "expectedRequestId", ""));
        assertEquals(HttpStatus.ERROR, controller.record(body, mock(HttpServletRequest.class)).get(AjaxResult.CODE_TAG));
        stubReadback(service, body);
        ConsentRecord collision = service.byRequestId(1001L, "home-city-consent-002");
        collision.setScene("roam_location");
        when(location.record(eq(1001L), eq("home_city_location"), eq("AGREE"),
                eq("home-city-consent-002"), eq(""), anyString())).thenReturn(collision);
        assertEquals(HttpStatus.ERROR, controller.record(body, mock(HttpServletRequest.class)).get(AjaxResult.CODE_TAG));
        body.put("docType", "bank_account_collection");
        assertEquals(HttpStatus.ERROR, controller.latest(body).get(AjaxResult.CODE_TAG));
    }

    @Test
    void anonymousHomeCityConsentCannotReadOrWrite() {
        IConsentRecordService service = mock(IConsentRecordService.class);
        ApiComplianceController controller = new ApiComplianceController() {
            @Override public Long getAppUserId() { throw new IllegalStateException("not logged in"); }
        };
        ReflectionTestUtils.setField(controller, "consentRecordService", service);
        ReflectionTestUtils.setField(controller, "complianceProperties", complianceProps());
        Map<String, Object> body = new HashMap<>(Map.of("docType", "privacy_policy",
                "scene", "home_city_location", "eventType", "AGREE", "requestId", "home-city-consent-003"));
        assertEquals(HttpStatus.ERROR, controller.record(body, mock(HttpServletRequest.class)).get(AjaxResult.CODE_TAG));
        assertEquals(HttpStatus.ERROR, controller.latest(body).get(AjaxResult.CODE_TAG));
        org.mockito.Mockito.verifyNoInteractions(service);
    }

    private static class TestApiComplianceController extends ApiComplianceController {
        @Override
        public Long getAppUserId() {
            return 1001L;
        }
    }

    @Test void roamConsentUsesCasServiceAndClientPlaySceneCannotSelectAnotherPermissionPath() {
        var generic=mock(IConsentRecordService.class);
        var location=mock(com.chengyinhub.business.service.impl.RoamLocationConsentService.class);
        var controller=new TestApiComplianceController();
        ReflectionTestUtils.setField(controller,"consentRecordService",generic);
        ReflectionTestUtils.setField(controller,"roamLocationConsentService",location);
        ReflectionTestUtils.setField(controller,"complianceProperties",complianceProps());
        Map<String,Object> body=new HashMap<>(Map.of("docType","privacy_policy","scene","roam_location","eventType","AGREE","requestId","new-agree","expectedRequestId","revoked"));
        ConsentRecord receipt=new ConsentRecord();receipt.setRequestId("new-agree");receipt.setMemberId(1001L);
        receipt.setDocType("privacy_policy");receipt.setDocVersion("v2.1");receipt.setScene("roam_location");receipt.setEventType("AGREE");
        when(location.record(eq(1001L),eq("AGREE"),eq("new-agree"),eq("revoked"),anyString())).thenReturn(receipt);
        assertEquals(200,controller.record(body,mock(HttpServletRequest.class)).get("code"));
        verify(location).record(eq(1001L),eq("AGREE"),eq("new-agree"),eq("revoked"),anyString());
        org.mockito.Mockito.verifyNoInteractions(generic);
        when(location.record(eq(1001L),eq("AGREE"),eq("new-agree"),eq("revoked"),anyString()))
                .thenThrow(new com.chengyinhub.business.service.impl.RoamLocationConsentService.ConsentChangedException());
        assertEquals(409,controller.record(body,mock(HttpServletRequest.class)).get("code"));
        body.put("scene","play");
        assertEquals(500,controller.record(body,mock(HttpServletRequest.class)).get("code"));
        org.mockito.Mockito.verifyNoInteractions(generic);
    }
}
