package com.chengyinhub.business.longrun;

import com.chengyinhub.business.service.IWxContentSecurityService;
import com.chengyinhub.business.service.ai.*;
import com.chengyinhub.business.service.ai.impl.AiGatewayServiceImpl;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** NOT_RUN: explicitly distinguishes stage failure from a possibly sent model call. */
class OWN_C05_11Test {
    @Test
    void onlyPreChatStageFailureRefundsOriginalReservation() {
        for (boolean failStage : new boolean[] {true, false}) {
            final String reservation = "ai:gen:limit:gen:7:20261015|0123456789abcdef0123456789abcdef";
            final String[] refunded = new String[1];
            AiGatewayServiceImpl gateway = new AiGatewayServiceImpl() {
                @Override public String consumeQuota(Long member, String biz) { return reservation; }
                @Override public void stageQuota(Long member, String biz, String token, String stage) {
                    if (failStage && "MODEL_STARTED".equals(stage)) throw new IllegalStateException("stage unavailable");
                }
                @Override public void refundQuota(Long member, String biz, String token) { refunded[0] = token; }
            };
            LlmClient client = mock(LlmClient.class);
            when(client.name()).thenReturn("deepseek");
            when(client.chat(anyString(), anyString())).thenThrow(new IllegalStateException("response lost"));
            ReflectionTestUtils.setField(gateway, "sceneRouter", new AiSceneRouter());
            ReflectionTestUtils.setField(gateway, "llmClientsByBeanName", Collections.singletonMap("deepseekLlmClient", client));
            ReflectionTestUtils.setField(gateway, "wxContentSecurityService", mock(IWxContentSecurityService.class));
            AiServiceException failure = assertThrows(AiServiceException.class,
                    () -> gateway.generate(7L, "merchant", "merchant_plan", "system", "user"));
            if (failStage) {
                assertEquals(AiServiceException.Reason.UPSTREAM_UNAVAILABLE, failure.getReason());
                assertEquals(reservation, refunded[0]);
                verify(client, never()).chat(anyString(), anyString());
            } else {
                assertTrue(failure instanceof AiProviderOutcomeUnknownException);
                assertNull(refunded[0]);
                verify(client, times(1)).chat("system", "user");
            }
        }
    }
}
