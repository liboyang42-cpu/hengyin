package com.chengyinhub.business.growthloops;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.chengyinhub.business.mapper.growthloops.GrowthLoopsMapper;
import com.chengyinhub.business.push.MemberNotice;
import com.chengyinhub.business.service.IPushFanoutService;
import com.chengyinhub.system.service.ISysConfigService;

class WinbackServiceTest
{
    private GrowthLoopsMapper mapper;
    private IPushFanoutService push;
    private final Map<String, String> config = new HashMap<>();
    private WinbackService service;

    @BeforeEach
    void setUp()
    {
        mapper = mock(GrowthLoopsMapper.class);
        push = mock(IPushFanoutService.class);
        ISysConfigService cfg = mock(ISysConfigService.class);
        when(cfg.selectConfigByKey(anyString())).thenAnswer(i -> config.get(i.getArgument(0)));
        service = new WinbackService(mapper, new GrowthLoopsSettings(cfg), push);
        when(push.apnsConfigured()).thenReturn(true);
        when(mapper.claimSend(anyLong(), any(), any(), anyInt(), anyString(), anyString())).thenReturn(1);
    }

    private static Date at(int hour)
    {
        return Date.from(ZonedDateTime.of(2026, 11, 15, hour, 0, 0, 0, ZoneId.of("Asia/Shanghai")).toInstant());
    }

    private static Map<String, Object> row(Object... kv)
    {
        Map<String, Object> m = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    @Test
    void offByDefaultTouchesNothing()
    {
        WinbackService.Summary s = service.runDue(at(12));
        assertEquals("OFF", s.mode());
        verifyNoInteractions(mapper);
        verify(push, never()).notifyMemberNow(anyLong(), any());
    }

    @Test
    void onWithoutApnsAndQuietHoursSendNothing()
    {
        config.put("growth.winback.mode", "ON");
        when(push.apnsConfigured()).thenReturn(false);
        assertEquals("APNs not configured", service.runDue(at(12)).note());
        when(push.apnsConfigured()).thenReturn(true);
        assertEquals("quiet hours", service.runDue(at(22)).note());
        assertEquals("quiet hours", service.runDue(at(7)).note());
        verifyNoInteractions(mapper);
    }

    @Test
    void streakAtRiskIsSentInTheEveningAndClaimedOnce()
    {
        config.put("growth.winback.mode", "ON");
        when(mapper.selectStreakAtRisk(any(), any(), eq(3), any(), eq(2), any(), eq(200)))
                .thenReturn(List.of(row("memberId", 7L, "streak", 6)));
        when(push.notifyMemberNow(eq(7L), any())).thenReturn(1);
        WinbackService.Summary s = service.runDue(at(19));
        assertEquals(1, s.sent());
        ArgumentCaptor<MemberNotice> notice = ArgumentCaptor.forClass(MemberNotice.class);
        verify(push).notifyMemberNow(eq(7L), notice.capture());
        assertEquals("/subpackageP3/pages/growthcenter/index/index", notice.getValue().page());
        verify(mapper).claimSend(eq(7L), any(), any(), eq(2), eq("STREAK_AT_RISK"), eq("streak:6"));
        verify(mapper).insertLog(eq(7L), eq("STREAK_AT_RISK"), anyString(), eq("SENT"), eq(1), any());
        verify(mapper, never()).releaseSend(anyLong(), any(), any(), anyInt());
    }

    @Test
    void streakIsNotCheckedBeforeTheEvening()
    {
        config.put("growth.winback.mode", "ON");
        service.runDue(at(12));
        verify(mapper, never()).selectStreakAtRisk(any(), any(), anyInt(), any(), anyInt(), any(), anyInt());
    }

    @Test
    void sentBeforeIsAtLeastAWeekEvenIfConfiguredShorter()
    {
        config.put("growth.winback.mode", "ON");
        config.put("growth.winback.minGapDays", "2");
        config.put("growth.winback.maxIgnored", "9");
        Date now = at(19);
        service.runDue(now);
        ArgumentCaptor<Date> sentBefore = ArgumentCaptor.forClass(Date.class);
        verify(mapper).selectLapsed(any(), any(), sentBefore.capture(), eq(2), any(), anyInt());
        assertEquals(7L * 24 * 3600 * 1000, now.getTime() - sentBefore.getValue().getTime());
    }

    @Test
    void undeliveredSendIsReleasedSoTheWeekIsNotBurned()
    {
        config.put("growth.winback.mode", "ON");
        when(mapper.selectStreakAtRisk(any(), any(), anyInt(), any(), anyInt(), any(), anyInt()))
                .thenReturn(List.of(row("memberId", 7L, "streak", 4)));
        Date prev = new Date(1_000L);
        when(mapper.selectState(7L)).thenReturn(row("lastSent", prev, "awaiting", 0));
        when(push.notifyMemberNow(eq(7L), any())).thenReturn(0);
        Date now = at(19);
        assertEquals(0, service.runDue(now).sent());
        verify(mapper).releaseSend(7L, now, prev, 0);
        verify(mapper).insertLog(eq(7L), anyString(), anyString(), eq("NOT_DELIVERED"), eq(0), any());
    }

    @Test
    void shadowModeLogsButNeverSends()
    {
        config.put("growth.winback.mode", "shadow");
        when(push.apnsConfigured()).thenReturn(false);
        when(mapper.selectStreakAtRisk(any(), any(), anyInt(), any(), anyInt(), any(), anyInt()))
                .thenReturn(List.of(row("memberId", 7L, "streak", 4)));
        WinbackService.Summary s = service.runDue(at(19));
        assertEquals(1, s.shadow());
        verify(push, never()).notifyMemberNow(anyLong(), any());
        verify(mapper, never()).claimSend(anyLong(), any(), any(), anyInt(), anyString(), anyString());
        verify(mapper).insertLog(eq(7L), eq("STREAK_AT_RISK"), anyString(), eq("SHADOW"), eq(0), any());
    }

    @Test
    void lapsedPlayerGetsClubEventBeforeNewPlaces()
    {
        config.put("growth.winback.mode", "ON");
        Date lastActive = new Date(at(12).getTime() - 10L * 24 * 3600 * 1000);
        when(mapper.selectLapsed(any(), any(), any(), anyInt(), any(), anyInt()))
                .thenReturn(List.of(row("memberId", 9L, "lastActive", lastActive)));
        when(mapper.selectExploredPrefixes(eq(9L), anyInt())).thenReturn(List.of("u4pru"));
        when(mapper.selectNewPois(any(), anyInt())).thenReturn(List.of(
                row("id", 31L, "name", "新咖啡馆", "lat", 57.64911, "lng", 10.40744, "createTime", at(10))));
        when(mapper.selectUpcomingActivities(any(), any(), anyInt())).thenReturn(List.of(
                row("id", 41L, "name", "远处的活动", "lat", "30.0", "lng", "120.0", "clubId", 0L, "startDate", at(15)),
                row("id", 42L, "name", "俱乐部夜跑", "lat", "30.0", "lng", "120.0", "clubId", 5L, "startDate", at(15))));
        when(mapper.selectMemberClubIds(9L)).thenReturn(List.of(5L));
        when(push.notifyMemberNow(eq(9L), any())).thenReturn(1);
        service.runDue(at(12));
        ArgumentCaptor<MemberNotice> notice = ArgumentCaptor.forClass(MemberNotice.class);
        verify(push).notifyMemberNow(eq(9L), notice.capture());
        assertEquals("/pages/activity/detail/index?id=42", notice.getValue().page());
    }

    @Test
    void lapsedPlayerGetsNewPlacesInTheirExploredAreaOnly()
    {
        config.put("growth.winback.mode", "ON");
        Date lastActive = new Date(at(12).getTime() - 10L * 24 * 3600 * 1000);
        when(mapper.selectLapsed(any(), any(), any(), anyInt(), any(), anyInt()))
                .thenReturn(List.of(row("memberId", 9L, "lastActive", lastActive), row("memberId", 10L, "lastActive", lastActive)));
        when(mapper.selectExploredPrefixes(eq(9L), anyInt())).thenReturn(List.of("u4pru"));
        when(mapper.selectExploredPrefixes(eq(10L), anyInt())).thenReturn(List.of("wtw3s"));
        when(mapper.selectNewPois(any(), anyInt())).thenReturn(List.of(
                row("id", 31L, "name", "新咖啡馆", "lat", 57.64911, "lng", 10.40744, "createTime", at(10)),
                row("id", 32L, "name", "旧地点", "lat", 57.64911, "lng", 10.40744, "createTime", new Date(lastActive.getTime() - 1000))));
        when(push.notifyMemberNow(eq(9L), any())).thenReturn(1);
        WinbackService.Summary s = service.runDue(at(12));
        assertEquals(1, s.sent());
        assertEquals(1, s.skipped());
        ArgumentCaptor<MemberNotice> notice = ArgumentCaptor.forClass(MemberNotice.class);
        verify(push).notifyMemberNow(eq(9L), notice.capture());
        assertEquals("/subpackageRoam/poi-detail/index?id=31", notice.getValue().page());
        assertFalse(notice.getValue().message(true).body().contains("more"));
        verify(mapper).touchEvaluated(eq(10L), any());
    }

    @Test
    void resolutionUpdatesOnlyChangedRows()
    {
        config.put("growth.winback.mode", "ON");
        Date now = at(12);
        Date sent = new Date(now.getTime() - 8L * 24 * 3600 * 1000);
        when(mapper.selectPendingResolution(anyInt())).thenReturn(List.of(
                row("memberId", 1L, "lastSent", sent, "awaiting", 1, "ignored", 0, "lastActive", new Date(sent.getTime() - 1)),
                row("memberId", 2L, "lastSent", sent, "awaiting", 1, "ignored", 1, "lastActive", new Date(sent.getTime() + 1)),
                row("memberId", 3L, "lastSent", now, "awaiting", 1, "ignored", 0, "lastActive", sent)));
        assertEquals(2, service.runDue(now).resolved());
        verify(mapper).updateResolution(1L, 0, 1);
        verify(mapper).updateResolution(2L, 0, 0);
        verify(mapper, never()).updateResolution(eq(3L), anyInt(), anyInt());
    }

    @Test
    void preferenceOptOut()
    {
        when(mapper.selectState(4L)).thenReturn(row("optedOut", 1));
        Map<String, Object> pref = service.setEnabled(4L, false);
        verify(mapper).upsertOptOut(eq(4L), eq(1), any());
        assertEquals(false, pref.get("enabled"));
        assertEquals("OFF", pref.get("serviceMode"));
    }
}
