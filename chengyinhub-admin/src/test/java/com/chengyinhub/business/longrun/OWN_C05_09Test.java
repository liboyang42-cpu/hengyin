package com.chengyinhub.business.longrun;

import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.ai.AiSceneRouter;
import com.chengyinhub.business.service.ai.AiServiceException;
import com.chengyinhub.business.service.ai.LlmClient;
import com.chengyinhub.business.service.ai.impl.AiGatewayServiceImpl;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** NOT_RUN: no provider or Redis calls. */
class OWN_C05_09Test {
    @Test
    void metadataFailureAfterSelectionStillRefundsOriginalUnsentReservation() {
        final String original = "ai:gen:limit:gen:7:20261015|0123456789abcdef0123456789abcdef";
        final String[] refunded = new String[1];
        AiGatewayServiceImpl gateway = new AiGatewayServiceImpl() {
            @Override public String consumeQuota(Long member, String biz) { return original; }
            @Override public void stageQuota(Long member, String biz, String token, String stage) {}
            @Override public void refundQuota(Long member, String biz, String token) { refunded[0] = token; }
        };
        LlmClient client = mock(LlmClient.class);
        when(client.name()).thenReturn("deepseek").thenThrow(new IllegalStateException("metadata unavailable"));
        ReflectionTestUtils.setField(gateway, "sceneRouter", new AiSceneRouter());
        ReflectionTestUtils.setField(gateway, "llmClientsByBeanName", Collections.singletonMap("deepseekLlmClient", client));
        ReflectionTestUtils.setField(gateway, "wxContentSecurityService", mock(IWxContentSecurityService.class));
        AiServiceException failure = assertThrows(AiServiceException.class,
                () -> gateway.generate(7L, "merchant", "merchant_plan", "system", "user"));
        assertEquals(AiServiceException.Reason.UPSTREAM_UNAVAILABLE, failure.getReason());
        assertEquals(original, refunded[0]);
        verify(client, never()).chat(anyString(), anyString());
    }
}
