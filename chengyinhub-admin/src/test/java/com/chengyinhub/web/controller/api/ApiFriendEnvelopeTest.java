package com.chengyinhub.web.controller.api;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import com.chengyinhub.business.service.channel.platform.ChannelRejection;
import com.chengyinhub.business.service.friend.FriendService;
import com.chengyinhub.common.core.domain.AjaxResult;

import static org.junit.jupiter.api.Assertions.*;

/** 好友/接龙/打卡信封：拒绝走 HTTP 200 + body.code + msg=稳定码；带回执的拒绝同时给 data；不确定走 HTTP 500。 */
class ApiFriendEnvelopeTest
{
    @Test void rejectionUsesBodyCodeWithStableMessage()
    {
        ResponseEntity<AjaxResult> r = ApiFriendController.envelope(() -> 1L, me -> { throw new ChannelRejection(409, "REQUEST_ID_CONFLICT", "x"); });
        assertEquals(200, r.getStatusCode().value());
        assertEquals(409, r.getBody().get("code"));
        assertEquals("REQUEST_ID_CONFLICT", r.getBody().get("msg"));
        assertEquals("REQUEST_ID_CONFLICT", r.getBody().get("errorCode"));
    }

    @Test void refusalReceiptKeepsDataAndDropsInternalStatus()
    {
        ResponseEntity<AjaxResult> r = ApiFriendController.envelope(() -> 1L, me -> {
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("outcome", "REJECTED");
            d.put("reason", "VERSION_CONFLICT");
            d.put(FriendService.STATUS_KEY, 409);
            return d;
        });
        assertEquals(409, r.getBody().get("code"));
        Map<?, ?> data = (Map<?, ?>) r.getBody().get("data");
        assertEquals("REJECTED", data.get("outcome"));
        assertFalse(data.containsKey(FriendService.STATUS_KEY));
    }

    @Test void unauthenticatedAndUnknown()
    {
        assertEquals(401, ApiFriendController.envelope(() -> null, me -> Map.of()).getStatusCode().value());
        ResponseEntity<AjaxResult> r = ApiFriendController.envelope(() -> 1L, me -> { throw new IllegalStateException("db"); });
        assertEquals(500, r.getStatusCode().value());
        assertEquals("OUTCOME_UNKNOWN", r.getBody().get("errorCode"));
    }
}
