package com.chengyinhub.business.longrun;

import com.chengyinhub.business.service.ai.AiServiceException;
import com.chengyinhub.business.service.ai.impl.AiGatewayServiceImpl;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Source-only regression; execution deliberately deferred. */
class OWN_C05_01Test {
    @Test
    void inputStageFailureCompensatesOriginalReservationBeforeModelSelection() {
        final String original = "ai:gen:limit:gen:7:20261015|0123456789abcdef0123456789abcdef";
        final String[] refunded = new String[1];
        AiGatewayServiceImpl gateway = new AiGatewayServiceImpl() {
            @Override public String consumeQuota(Long member, String biz) { return original; }
            @Override public void stageQuota(Long member, String biz, String token, String stage) {
                assertEquals(original, token);
                assertEquals("INPUT_REVIEW_STARTED", stage);
                throw new IllegalStateException("stage persistence unavailable");
            }
            @Override public void refundQuota(Long member, String biz, String token) {
                assertEquals(Long.valueOf(7), member);
                assertEquals("theme_draft", biz);
                refunded[0] = token;
            }
        };
        assertThrows(AiServiceException.class,
                () -> gateway.generate(7L, "merchant", "theme_draft", "system", "user"));
        assertEquals(original, refunded[0]);
    }
}
