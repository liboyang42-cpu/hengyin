package com.chengyinhub.business.growthloops;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalTime;
import java.util.Date;

import org.junit.jupiter.api.Test;

import com.chengyinhub.business.push.MemberNotice;
import com.chengyinhub.business.push.PushMessage;

class WinbackPolicyTest
{
    private static final long DAY = 24L * 3600L * 1000L;

    @Test
    void quietHoursAcrossMidnightAndSameDay()
    {
        LocalTime s = LocalTime.of(21, 0), e = LocalTime.of(9, 0);
        assertTrue(WinbackPolicy.inQuiet(LocalTime.of(23, 30), s, e));
        assertTrue(WinbackPolicy.inQuiet(LocalTime.of(8, 59), s, e));
        assertFalse(WinbackPolicy.inQuiet(LocalTime.of(9, 0), s, e));
        assertFalse(WinbackPolicy.inQuiet(LocalTime.of(20, 59), s, e));
        assertTrue(WinbackPolicy.inQuiet(LocalTime.of(13, 30), LocalTime.of(13, 0), LocalTime.of(14, 0)));
        assertFalse(WinbackPolicy.inQuiet(LocalTime.of(13, 30), LocalTime.of(9, 0), LocalTime.of(9, 0)));
    }

    @Test
    void returningClearsIgnoredAndSilenceCountsOnceAfterAWeek()
    {
        Date sent = new Date(100 * DAY);
        // came back after the message -> reset
        assertEquals(new WinbackPolicy.Resolution(false, 0),
                WinbackPolicy.resolve(true, 1, sent, new Date(101 * DAY), new Date(103 * DAY), 7));
        // came back even after being at the cap -> reset, so they can be reminded again later
        assertEquals(new WinbackPolicy.Resolution(false, 0),
                WinbackPolicy.resolve(false, 2, sent, new Date(110 * DAY), new Date(111 * DAY), 7));
        // still waiting inside the week -> unchanged
        assertEquals(new WinbackPolicy.Resolution(true, 1),
                WinbackPolicy.resolve(true, 1, sent, new Date(90 * DAY), new Date(105 * DAY), 7));
        // a week of silence -> one more ignored, no longer awaiting (not counted twice)
        WinbackPolicy.Resolution r = WinbackPolicy.resolve(true, 1, sent, new Date(90 * DAY), new Date(107 * DAY), 7);
        assertEquals(new WinbackPolicy.Resolution(false, 2), r);
        assertEquals(r, WinbackPolicy.resolve(r.awaiting(), r.ignored(), sent, new Date(90 * DAY), new Date(120 * DAY), 7));
    }

    @Test
    void contactRulesWeekGapIgnoredCapAndOptOut()
    {
        Date now = new Date(200 * DAY);
        assertTrue(WinbackPolicy.mayContact(false, 0, null, now, 7, 2));
        assertFalse(WinbackPolicy.mayContact(false, 0, new Date(194 * DAY), now, 7, 2));
        assertTrue(WinbackPolicy.mayContact(false, 1, new Date(193 * DAY), now, 7, 2));
        assertFalse(WinbackPolicy.mayContact(false, 2, null, now, 7, 2));
        assertFalse(WinbackPolicy.mayContact(true, 0, null, now, 7, 2));
    }

    @Test
    void priorityStreakThenEventThenPlaces()
    {
        assertEquals(WinbackPolicy.Trigger.STREAK_AT_RISK, WinbackPolicy.choose(true, true, true));
        assertEquals(WinbackPolicy.Trigger.CLUB_EVENT, WinbackPolicy.choose(false, true, true));
        assertEquals(WinbackPolicy.Trigger.NEW_PLACES, WinbackPolicy.choose(false, false, true));
        assertNull(WinbackPolicy.choose(false, false, false));
    }

    @Test
    void noticesCarryBothLanguagesAndRoutablePages()
    {
        MemberNotice streak = WinbackPolicy.streakNotice(5);
        assertEquals(PushMessage.KIND_GROWTH, streak.kind());
        assertEquals("winback", streak.threadId());
        assertTrue(streak.message(false).title().contains("5"));
        assertEquals("Keep your 5-day streak", streak.message(true).title());
        assertEquals("/pages/activity/detail/index?id=12", WinbackPolicy.clubEventNotice(12, "夜跑", "11-16 19:00").page());
        MemberNotice places = WinbackPolicy.newPlacesNotice(8, "一个名字非常非常非常非常非常非常非常非常长的地方啊啊啊啊", 3);
        assertTrue(places.message(false).body().contains("…"));
        assertTrue(places.message(true).body().contains("2 more"));
        assertEquals("/subpackageRoam/poi-detail/index?id=8", places.page());
    }

    @Test
    void geohashMatchesReferenceValue()
    {
        assertEquals("u4pru", GeoCell.encode(57.64911, 10.40744, 5));
        assertEquals("u4pruydqqvj", GeoCell.encode(57.64911, 10.40744, 11));
        assertNull(GeoCell.encode("0", "0", 5));
        assertNull(GeoCell.encode("x", "1", 5));
        assertEquals("u4pru", GeoCell.encode("57.64911", "10.40744", 5));
    }
}
