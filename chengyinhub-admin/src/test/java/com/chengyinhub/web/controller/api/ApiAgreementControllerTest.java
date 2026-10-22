package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.AgreementDocument;
import com.chengyinhub.business.domain.ConsentRecord;
import com.chengyinhub.business.mapper.ConsentRecordMapper;
import com.chengyinhub.business.service.impl.ConsentRecordServiceImpl;
import com.chengyinhub.business.service.IAgreementDocumentService;
import com.chengyinhub.business.service.IConsentRecordService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.test.util.ReflectionTestUtils;

import jakarta.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ApiAgreementControllerTest {

    @Test
    void getsPublishedPrivacyDocumentWithoutAUserSession() {
        IAgreementDocumentService documents = mock(IAgreementDocumentService.class);
        AgreementDocument document = document("privacy", "v2.0");
        when(documents.getActive("privacy")).thenReturn(document);
        ApiAgreementController controller = controller(documents, mock(IConsentRecordService.class));

        AjaxResult result = controller.get("privacy");

        assertEquals(HttpStatus.SUCCESS, result.get(AjaxResult.CODE_TAG));
        assertEquals(document, result.get(AjaxResult.DATA_TAG));
    }

    @Test
    void consentUsesActiveServerVersionInsteadOfClientVersion() {
        IAgreementDocumentService documents = mock(IAgreementDocumentService.class);
        IConsentRecordService consents = mock(IConsentRecordService.class);
        when(documents.getActive("privacy")).thenReturn(document("privacy", "v2.0"));
        ApiAgreementController controller = controller(documents, consents);
        Map<String, Object> body = new HashMap<>();
        body.put("type", "privacy");
        body.put("version", "client-forged-v0");
        body.put("requestId", "agreement-consent-001");

        AjaxResult result = controller.consent(body, mock(HttpServletRequest.class));

        assertEquals(HttpStatus.SUCCESS, result.get(AjaxResult.CODE_TAG));
        verify(consents).record(eq(1001L), eq("privacy"), eq("v2.0"), eq("app_launch"), eq("AGREE"),
                eq("agreement-consent-001"), anyString());
    }

    @Test
    void sameRequestReplayOfSameConsentEventIsIdempotentSuccess() {
        ConsentRecordMapper mapper = duplicateMapper(stored(1001L, "privacy", "v2.0", "AGREE"));
        ApiAgreementController controller = controller(activeDocuments(), realService(mapper));

        AjaxResult replay = controller.consent(body("privacy", "agreement-consent-001"), mock(HttpServletRequest.class));

        assertEquals(HttpStatus.SUCCESS, replay.get(AjaxResult.CODE_TAG));
        verify(mapper).selectByRequestIdForShare("agreement-consent-001");
    }

    @Test
    void sameRequestIdForDifferentConsentEventReturnsExplicitConflict() {
        // 同一 requestId 原事件是 user_agreement / 旧版本 / REVOKE：都不是本次 privacy v2.0 AGREE。
        for (ConsentRecord original : new ConsentRecord[]{
                stored(1001L, "user_agreement", "v2.0", "AGREE"),
                stored(1001L, "privacy", "v1.9", "AGREE"),
                stored(1001L, "privacy", "v2.0", "REVOKE")}) {
            ApiAgreementController controller = controller(activeDocuments(), realService(duplicateMapper(original)));

            AjaxResult result = controller.consent(body("privacy", "agreement-consent-001"), mock(HttpServletRequest.class));

            assertConflict(result);
        }
    }

    @Test
    void anotherAccountCannotReuseRequestIdAndOriginalIsNotEchoed() {
        ConsentRecord othersEvent = stored(2002L, "privacy", "v2.0", "AGREE");
        ApiAgreementController controller = controller(activeDocuments(), realService(duplicateMapper(othersEvent)));

        AjaxResult result = controller.consent(body("privacy", "agreement-consent-001"), mock(HttpServletRequest.class));

        assertConflict(result);
        assertNull(result.get(AjaxResult.DATA_TAG));
        assertFalse(String.valueOf(result).contains("2002"));
    }

    @Test
    void duplicateWithoutReadableOriginalFailsClosed() {
        ApiAgreementController controller = controller(activeDocuments(), realService(duplicateMapper(null)));

        assertConflict(controller.consent(body("privacy", "agreement-consent-001"), mock(HttpServletRequest.class)));
    }

    private static void assertConflict(AjaxResult result) {
        assertEquals(HttpStatus.CONFLICT, result.get(AjaxResult.CODE_TAG));
        assertEquals("CONSENT_REQUEST_CONFLICT", result.get("errorCode"));
        assertEquals("请求标识已用于其他同意事件", result.get(AjaxResult.MSG_TAG));
    }

    private IAgreementDocumentService activeDocuments() {
        IAgreementDocumentService documents = mock(IAgreementDocumentService.class);
        when(documents.getActive("privacy")).thenReturn(document("privacy", "v2.0"));
        return documents;
    }

    private static ConsentRecordMapper duplicateMapper(ConsentRecord original) {
        ConsentRecordMapper mapper = mock(ConsentRecordMapper.class);
        when(mapper.insertConsentRecord(any(ConsentRecord.class))).thenThrow(new DuplicateKeyException("private constraint details"));
        when(mapper.selectByRequestIdForShare("agreement-consent-001")).thenReturn(original);
        return mapper;
    }

    private static IConsentRecordService realService(ConsentRecordMapper mapper) {
        ConsentRecordServiceImpl service = new ConsentRecordServiceImpl();
        ReflectionTestUtils.setField(service, "consentRecordMapper", mapper);
        return service;
    }

    private static ConsentRecord stored(Long memberId, String docType, String version, String eventType) {
        ConsentRecord record = new ConsentRecord();
        record.setMemberId(memberId);
        record.setDocType(docType);
        record.setDocVersion(version);
        record.setScene("app_launch");
        record.setEventType(eventType);
        record.setRequestId("agreement-consent-001");
        return record;
    }

    private static Map<String, Object> body(String type, String requestId) {
        Map<String, Object> body = new HashMap<>();
        body.put("type", type);
        body.put("requestId", requestId);
        return body;
    }

    private ApiAgreementController controller(IAgreementDocumentService documents, IConsentRecordService consents) {
        ApiAgreementController controller = new ApiAgreementController() {
            @Override
            public Long getAppUserId() {
                return 1001L;
            }
        };
        ReflectionTestUtils.setField(controller, "agreementDocumentService", documents);
        ReflectionTestUtils.setField(controller, "consentRecordService", consents);
        return controller;
    }

    private AgreementDocument document(String type, String version) {
        AgreementDocument document = new AgreementDocument();
        document.setDocType(type);
        document.setVersion(version);
        document.setTitle("协议");
        document.setContent("正文");
        document.setStatus(1);
        return document;
    }
}
