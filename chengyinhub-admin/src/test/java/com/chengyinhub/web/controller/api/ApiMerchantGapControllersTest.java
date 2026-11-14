package com.chengyinhub.web.controller.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.domain.vo.ai.NpcChatResp;
import com.chengyinhub.business.merchantgap.MerchantGapException;
import com.chengyinhub.business.service.impl.NpcChatService;
import com.chengyinhub.business.service.merchantauthoring.MerchantAuthoringService;
import com.chengyinhub.common.core.domain.AjaxResult;

/** Controller wiring: authoring/player/{merchantId}/requests/{requestId}, the three legacy aliases and the refusal envelope. */
class ApiMerchantGapControllersTest {
    static final String RID = "5B8F1D2A-0C3E-4F6A-9B7C-1D2E3F4A5B6C";

    ApiMerchantAuthoringController authoring(NpcChatService chat) {
        ApiMerchantAuthoringController controller = new ApiMerchantAuthoringController() {
            @Override
            public Long getAppUserId() { return 9004L; }
        };
        ReflectionTestUtils.setField(controller, "npcChat", chat);
        ReflectionTestUtils.setField(controller, "authoring", mock(MerchantAuthoringService.class));
        return controller;
    }

    @Test
    @SuppressWarnings("unchecked")
    void playerRequestMapsTheStoredStoreChatRequest() {
        NpcChatService chat = mock(NpcChatService.class);
        NpcChatResp stored = new NpcChatResp();
        stored.setRequestId(RID);
        stored.setOutcomeStatus("SUCCEEDED");
        stored.setSafetyDecision("PASS");
        stored.setSafeText("欢迎再来");
        stored.setRetryable(false);
        stored.setAi(true);
        when(chat.readOriginalRequest("merchant", 8001L, RID, 9004L)).thenReturn(stored);
        ResponseEntity<AjaxResult> ok = authoring(chat).playerRequest(8001L, RID, new MockHttpServletResponse());
        assertEquals(200, ok.getStatusCode().value());
        Map<String, Object> data = (Map<String, Object>) ok.getBody().get(AjaxResult.DATA_TAG);
        assertEquals(8001L, data.get("merchantId"));
        assertEquals("SUCCEEDED", data.get("outcomeStatus"));
        assertEquals("PASS", data.get("safetyDecision"));
        assertEquals(false, data.get("retryable"));
        assertEquals(true, data.get("ai"));

        NpcChatResp missing = new NpcChatResp();
        missing.setOutcomeStatus(NpcChatService.STATUS_NOT_FOUND);
        when(chat.readOriginalRequest(any(), any(), any(), any())).thenReturn(missing);
        ResponseEntity<AjaxResult> notFound = authoring(chat).playerRequest(8001L, "00000000-0000-4000-8000-000000000000", new MockHttpServletResponse());
        assertEquals(404, notFound.getStatusCode().value());
        assertEquals(404, notFound.getBody().get(AjaxResult.CODE_TAG));
        assertEquals("REQUEST_NOT_FOUND", notFound.getBody().get("errorCode"));
        assertEquals(400, authoring(chat).playerRequest(8001L, "nope", new MockHttpServletResponse()).getStatusCode().value());
    }

    @Test
    void legacyNamesForwardToTheServedRoutes() {
        ApiMerchantLegacyAliasController alias = new ApiMerchantLegacyAliasController();
        assertEquals("forward:/api/ai/merchant/insight", alias.insight());
        assertEquals("forward:/api/merchant/public-detail", alias.poiDetail());
        assertEquals("forward:/api/merchant/merchant_registration", alias.shopApply());
    }

    @Test
    void refusalUsesTheSameHttpStatusAndCode() {
        ResponseEntity<AjaxResult> refused = ApiMerchantGapController.answer(() -> {
            throw new MerchantGapException(409, "REQUEST_ID_CONFLICT", "x");
        });
        assertEquals(409, refused.getStatusCode().value());
        assertEquals(409, refused.getBody().get(AjaxResult.CODE_TAG));
        assertEquals("REQUEST_ID_CONFLICT", refused.getBody().get("errorCode"));
    }
}
