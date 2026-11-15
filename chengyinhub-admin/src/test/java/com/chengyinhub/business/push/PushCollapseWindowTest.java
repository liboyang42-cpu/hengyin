package com.chengyinhub.business.push;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class PushCollapseWindowTest
{
    @Test
    void atMostOnePushPerMemberAndThreadWithinTheWindow()
    {
        AtomicLong now = new AtomicLong(1_000_000L);
        PushCollapseWindow window = new PushCollapseWindow(30_000L, now::get);
        assertTrue(window.tryAcquire(1L, "chat-9"));
        now.addAndGet(29_999L);
        assertFalse(window.tryAcquire(1L, "chat-9"));
        assertTrue(window.tryAcquire(2L, "chat-9"), "其他会员不受影响");
        assertTrue(window.tryAcquire(1L, "chat-10"), "其他会话不受影响");
        now.addAndGet(1L);
        assertTrue(window.tryAcquire(1L, "chat-9"), "窗口过后可以再推");
    }
}
