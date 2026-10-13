package com.chengyinhub.web.controller.api;

import com.chengyinhub.business.domain.AgreementDocument;
import com.chengyinhub.business.service.IAgreementDocumentService;
import com.chengyinhub.business.service.IConsentRecordService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.core.domain.AjaxResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
