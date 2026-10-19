package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.notify.NotifyChannel;
import com.chengyinhub.business.notify.NotifyMessage;
import com.chengyinhub.business.notify.NotifyResult;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NotifyTypedResultTest {

    @Test
    void officialBroadcastKeepsUnknownAndStableChannelIdentity() {
        NotifyServiceImpl service = serviceWith(new NotifyChannel() {
            @Override public String channelId() { return "WECHAT_SUBSCRIBE"; }
            @Override public boolean send(NotifyMessage message) { return false; }
            @Override public NotifyResult sendResult(NotifyMessage message) {
                return NotifyResult.unknown("WECHAT_SUBSCRIBE");
            }
        });

        NotifyResult result = service.notifyOfficialBroadcastResult(7L, "活动", "即将开始");

        assertEquals(NotifyResult.Status.UNKNOWN, result.getStatus());
        assertEquals("WECHAT_SUBSCRIBE", result.getChannel());
        assertFalse(service.notifyOfficialBroadcast(7L, "活动", "即将开始"));
    }

    @Test
    void confirmedMeansAcceptedNotDeviceDelivered() {
        NotifyServiceImpl service = serviceWith(new NotifyChannel() {
            @Override public boolean send(NotifyMessage message) { return true; }
            @Override public String channelId() { return "WECHAT_SUBSCRIBE"; }
        });

        NotifyResult result = service.notifyBeforeStartResult(8L, "夜骑", "2026-10-12 20:00");

        assertEquals(NotifyResult.Status.CHANNEL_ACCEPTED, result.getStatus());
        assertTrue(result.isConfirmed());
        assertTrue(service.notifyBeforeStart(8L, "夜骑", "2026-10-12 20:00"));
    }

    @Test
    void throwableNeverEscapesBusinessPath() {
        NotifyServiceImpl service = serviceWith(new NotifyChannel() {
            @Override public String channelId() { return "WECHAT_SUBSCRIBE"; }
            @Override public boolean send(NotifyMessage message) { throw new AssertionError("broken sdk"); }
        });

        NotifyResult result = service.notifyBeforeStartResult(9L, "夜骑", "2026-10-12 20:00");

        assertEquals(NotifyResult.Status.UNKNOWN, result.getStatus());
        assertFalse(result.isConfirmed());
    }

    @Test
    void inAppCannotWinWechatResultOrReceiveTypedHostMessage() {
        java.util.concurrent.atomic.AtomicInteger inAppCalls = new java.util.concurrent.atomic.AtomicInteger();
        NotifyChannel inApp = new NotifyChannel() {
            @Override public String channelId() { return "IN_APP"; }
            @Override public boolean send(NotifyMessage message) { inAppCalls.incrementAndGet(); return true; }
        };
        NotifyChannel wechat = new NotifyChannel() {
            @Override public String channelId() { return "WECHAT_SUBSCRIBE"; }
            @Override public boolean send(NotifyMessage message) { return false; }
            @Override public NotifyResult sendResult(NotifyMessage message) { return NotifyResult.unknown(channelId()); }
        };
        NotifyServiceImpl service = new NotifyServiceImpl();
        ReflectionTestUtils.setField(service, "channels", java.util.Arrays.asList(inApp, wechat));
        assertEquals(NotifyResult.Status.UNKNOWN, service.notifyOfficialBroadcastResult(7L, "活动", "开始").getStatus());
        assertEquals(0, inAppCalls.get());
        assertTrue(service.notifyOfficialBroadcast(7L, "活动", "开始"));
        assertEquals(1, inAppCalls.get(), "旧 boolean 入口仍完整 fanout");
    }

    private NotifyServiceImpl serviceWith(NotifyChannel channel) {
        NotifyServiceImpl service = new NotifyServiceImpl();
        ReflectionTestUtils.setField(service, "channels", Collections.singletonList(channel));
        return service;
    }
}
