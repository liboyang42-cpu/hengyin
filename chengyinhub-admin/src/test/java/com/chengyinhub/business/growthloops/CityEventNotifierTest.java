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

    @Test
    void cityMonopolyCopyMatchesTheReviewTable() {
        IPushFanoutService push = mock(IPushFanoutService.class);
        CityEventNotifier notifier = new CityEventNotifier(push);
        notifier.monopolyLost(4L, "terr:T-1", "老街", 24, 0.5);
        notifier.defended(4L, "terr:T-1", "老街", 13, 15);
        notifier.decaying(4L, "terr:T-1", "老街", 5);
        notifier.released(4L, "terr:T-1", "老街");
        ArgumentCaptor<MemberNotice> c = ArgumentCaptor.forClass(MemberNotice.class);
        verify(push, times(4)).notifyMemberAfterCommit(eq(4L), c.capture());
        java.util.List<MemberNotice> all = c.getAllValues();
        assertEquals("「老街」被夺走了。24 小时内反击，挑战费减半。", all.get(0).message(false).body());
        assertEquals("老街 was taken. Strike back within 24 hours for half the fee.", all.get(0).message(true).body());
        assertEquals("「老街」守住了！对手掷出 13，没有达到 15。", all.get(1).message(false).body());
        assertEquals("老街 held! The challenger rolled 13, short of 15.", all.get(1).message(true).body());
        assertEquals("Nobody has looked after 老街 for 5 days. Visit soon or it loses a level.", all.get(2).message(true).body());
        assertEquals("老街 was left alone too long and is open again.", all.get(3).message(true).body());
        assertEquals("city-terr:T-1", all.get(0).threadId());
    }

}
