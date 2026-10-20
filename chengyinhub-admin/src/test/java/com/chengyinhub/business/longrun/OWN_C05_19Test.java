package com.chengyinhub.business.longrun;

import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.ai.*;
import com.chengyinhub.business.service.ai.impl.AiGatewayServiceImpl;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** NOT_RUN: retention failure must not erase the already-received provider fact. */
class OWN_C05_19Test {
    @Test
    void retentionFailureAfterEachOutputPathKeepsResultReceivedMarker() {
        for (int mode = 0; mode < 4; mode++) {
            final String[] decision = new String[1];
            AiGatewayServiceImpl gateway = new AiGatewayServiceImpl() {
                @Override public String consumeQuota(Long member, String biz) { return "original-reservation"; }
                @Override public void stageQuota(Long member, String biz, String token, String stage) {}
                @Override public void retainQuota(Long member, String biz, String token, String reason) {
                    decision[0] = reason;
                    throw new IllegalStateException("retention persistence unavailable");
                }
                @Override public void refundQuota(Long member, String biz, String token) { fail("must not refund received result"); }
            };
            LlmClient client = mock(LlmClient.class);
            when(client.name()).thenReturn("deepseek");
            when(client.chat(anyString(), anyString())).thenReturn("PRIVATE_MODEL_TEXT");
            IWxContentSecurityService security = mock(IWxContentSecurityService.class);
            if (mode == 0) when(security.checkText(anyString(), anyString()))
                    .thenReturn(null).thenThrow(new IllegalStateException("review unavailable"));
            else when(security.checkText(anyString(), anyString())).thenReturn(null)
                    .thenReturn(mode == 1 ? IWxContentSecurityService.MSG_BUSY : mode == 2 ? "生成内容违规" : null);
            ReflectionTestUtils.setField(gateway, "sceneRouter", new AiSceneRouter());
            ReflectionTestUtils.setField(gateway, "llmClientsByBeanName", Collections.singletonMap("deepseekLlmClient", client));
            ReflectionTestUtils.setField(gateway, "wxContentSecurityService", security);
            AiProviderResultReceivedException failure = assertThrows(AiProviderResultReceivedException.class,
                    () -> gateway.generate(7L, "merchant", "merchant_plan", "system", "user"));
            assertEquals(mode < 2 ? "OUTPUT_REVIEW_UNKNOWN" : mode == 2 ? "OUTPUT_REJECTED" : "SUCCESS", decision[0]);
            assertFalse(failure.getMessage().contains("PRIVATE_MODEL_TEXT"));
            assertEquals(AiServiceException.Reason.UPSTREAM_UNAVAILABLE, failure.getReason());
            verify(client, times(1)).chat("system", "user");
        }
    }
}
