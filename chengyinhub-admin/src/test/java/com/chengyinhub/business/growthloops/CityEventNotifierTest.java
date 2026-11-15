package com.chengyinhub.business.growthloops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.chengyinhub.business.push.MemberNotice;
import com.chengyinhub.business.push.PushMessage;
import com.chengyinhub.business.service.IPushFanoutService;

class CityEventNotifierTest
{
    @Test
    void challengedAndLostUseCityKindAndPerPlaceThread()
    {
        IPushFanoutService push = mock(IPushFanoutService.class);
        CityEventNotifier n = new CityEventNotifier(push);
        n.challenged(3L, "wx4g0b2", "鼓楼");
        n.lost(4L, "wx4g0b2", null);
        ArgumentCaptor<MemberNotice> c = ArgumentCaptor.forClass(MemberNotice.class);
        verify(push).notifyMemberAfterCommit(eq(3L), c.capture());
        verify(push).notifyMemberAfterCommit(eq(4L), c.capture());
        assertEquals(PushMessage.KIND_CITY, c.getAllValues().get(0).kind());
        assertEquals("city-wx4g0b2", c.getAllValues().get(0).threadId());
        assertEquals("/pages/roam/index", c.getAllValues().get(0).page());
        assertTrue(c.getAllValues().get(0).message(false).body().contains("鼓楼"));
        assertEquals("Go and take it back.", c.getAllValues().get(1).message(true).body());
    }

    @Test
    void weeklyYieldOncePerWeekAndCoinsOptional()
    {
        IPushFanoutService push = mock(IPushFanoutService.class);
        CityEventNotifier n = new CityEventNotifier(push);
        n.weeklyYield(3L, "2026-W46", 2, 40L);
        n.weeklyYield(3L, "2026-W46", 2, 40L);
        n.weeklyYield(3L, "2026-W47", 1, null);
        ArgumentCaptor<MemberNotice> c = ArgumentCaptor.forClass(MemberNotice.class);
        verify(push, times(2)).notifyMemberAfterCommit(eq(3L), c.capture());
        assertTrue(c.getAllValues().get(0).message(true).body().contains("40 city coins"));
        assertFalse(c.getAllValues().get(1).message(false).body().contains("城市币"));
    }

    @Test
    void threadKeyIsSanitisedAndFailuresAreSwallowed()
    {
        assertEquals("city", CityEventNotifier.thread(" "));
        assertEquals("city-ab12", CityEventNotifier.thread("a b/1\n2"));
        IPushFanoutService push = mock(IPushFanoutService.class);
        doThrow(new IllegalStateException()).when(push).notifyMemberAfterCommit(anyLong(), any());
        assertDoesNotThrow(() -> new CityEventNotifier(push).lost(1L, "x", "y"));
        assertDoesNotThrow(() -> new CityEventNotifier(null).lost(1L, "x", "y"));
    }
}
