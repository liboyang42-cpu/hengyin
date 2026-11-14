package com.chengyinhub.web.controller.api;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import com.chengyinhub.business.service.channel.platform.ChannelRejection;
import com.chengyinhub.common.core.domain.AjaxResult;

import static org.junit.jupiter.api.Assertions.*;

/** App 只在 HTTP 状态 == body.code 时把拒绝当确定结果；其余一律待确认。 */
class ChannelHttpResponseTest
{
    @Test void rejectionUsesSameHttpStatusAndCode()
    {
        ResponseEntity<AjaxResult> r = ChannelHttpResponse.run(() -> 5L, me -> { throw new ChannelRejection(409, "VERSION_CONFLICT", "x"); });
        assertEquals(409, r.getStatusCode().value());
        assertEquals(409, r.getBody().get(AjaxResult.CODE_TAG));
        assertEquals("VERSION_CONFLICT", r.getBody().get("errorCode"));
        assertEquals(true, r.getBody().get("outcome_known"));
    }

    @Test void unexpectedFailureIsOutcomeUnknown()
    {
        ResponseEntity<AjaxResult> r = ChannelHttpResponse.run(() -> 5L, me -> { throw new IllegalStateException("db"); });
        assertEquals(500, r.getStatusCode().value());
        assertEquals("OUTCOME_UNKNOWN", r.getBody().get("errorCode"));
        assertEquals(false, r.getBody().get("outcome_known"));
    }

    @Test void missingLoginIs401AndSuccessIs200()
    {
        assertEquals(401, ChannelHttpResponse.run(() -> { throw new RuntimeException("no token"); }, me -> "x").getStatusCode().value());
        ResponseEntity<AjaxResult> ok = ChannelHttpResponse.run(() -> 5L, me -> java.util.Map.of("me", me));
        assertEquals(200, ok.getStatusCode().value());
        assertEquals(200, ok.getBody().get(AjaxResult.CODE_TAG));
    }
}
