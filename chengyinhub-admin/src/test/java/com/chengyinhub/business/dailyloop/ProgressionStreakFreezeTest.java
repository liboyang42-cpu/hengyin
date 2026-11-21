package com.chengyinhub.business.dailyloop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.badge.RuleEvaluator;
import com.chengyinhub.business.domain.GrowthBadge;
import com.chengyinhub.business.domain.PlayerBadge;
import com.chengyinhub.business.domain.PlayerGrowth;
import com.chengyinhub.business.mapper.GrowthBadgeMapper;
import com.chengyinhub.business.mapper.PlayerBadgeMapper;
import com.chengyinhub.business.mapper.PlayerGrowthMapper;
import com.chengyinhub.business.service.ICmsTopicChapterService;
import com.chengyinhub.business.service.impl.PlayerProgressionServiceImpl;

/** P7 GAM-04 / GRO-07:节点与漫游共用连续天数,补签卡自动用,徽章阶梯 3/7/14/30。 */
class ProgressionStreakFreezeTest {

    private PlayerGrowthMapper growthMapper;
    private PlayerBadgeMapper badgeMapper;
    private GrowthBadgeMapper defMapper;
    private PlayerProgressionServiceImpl svc;

    private static Date day(LocalDate d) { return Date.from(d.atStartOfDay(ZoneId.systemDefault()).toInstant()); }

    private static GrowthBadge streakDef(int n) {
        GrowthBadge g = new GrowthBadge();
        g.setBadgeCode("STREAK_" + n); g.setBadgeName("streak " + n);
        g.setUnlockRuleJson("{\"aggregate\":\"distinct\",\"aggregate_field\":\"active_day\",\"threshold\":" + n
                + ",\"window\":{\"type\":\"natural_day_streak\"}}");
        return g;
    }

    private PlayerGrowth row(int streak, LocalDate last, int freezes) {
        PlayerGrowth g = new PlayerGrowth();
        g.setMemberId(1L); g.setLevel(1); g.setTotalCheckins(3);
        g.setStreakDays(streak); g.setLastCheckinDate(last == null ? null : day(last));
        g.setStreakFreezes(freezes); g.setStreakFreezesUsed(0);
        return g;
    }

    private PlayerGrowth written() {
        ArgumentCaptor<PlayerGrowth> c = ArgumentCaptor.forClass(PlayerGrowth.class);
        verify(growthMapper).updatePlayerGrowth(c.capture());
        return c.getValue();
    }

    @BeforeEach
    void setUp() {
        growthMapper = mock(PlayerGrowthMapper.class);
        badgeMapper = mock(PlayerBadgeMapper.class);
        defMapper = mock(GrowthBadgeMapper.class);
        svc = new PlayerProgressionServiceImpl();
        ReflectionTestUtils.setField(svc, "playerGrowthMapper", growthMapper);
        ReflectionTestUtils.setField(svc, "playerBadgeMapper", badgeMapper);
        ReflectionTestUtils.setField(svc, "growthBadgeMapper", defMapper);
        ReflectionTestUtils.setField(svc, "cmsTopicChapterService", mock(ICmsTopicChapterService.class));
        ReflectionTestUtils.setField(svc, "ruleEvaluator", new RuleEvaluator());
        for (int n : new int[] { 3, 7, 14, 30 }) when(defMapper.selectByCode("STREAK_" + n)).thenReturn(streakDef(n));
    }

    @Test
    void nodeAfterOneMissedDayUsesAFreezeAndKeepsTheStreak() {
        when(growthMapper.selectByMemberForUpdate(1L)).thenReturn(row(9, LocalDate.now().minusDays(2), 1));
        svc.onNodeDone(1L);
        PlayerGrowth w = written();
        assertEquals(10, w.getStreakDays());
        assertEquals(0, w.getStreakFreezes());
        assertEquals(1, w.getStreakFreezesUsed());
        assertEquals(4, w.getTotalCheckins(), "节点仍计累计打卡");
    }

    @Test
    void walkDayAdvancesStreakWithoutCountingACheckin() {
        when(growthMapper.selectByMemberForUpdate(1L)).thenReturn(row(13, LocalDate.now().minusDays(1), 1));
        List<PlayerBadge> newly = svc.onActiveDay(1L);
        PlayerGrowth w = written();
        assertEquals(14, w.getStreakDays());
        assertEquals(2, w.getStreakFreezes(), "满 14 天再得一张(上限 2)");
        assertNull(w.getTotalCheckins(), "漫游不加累计打卡数");
        assertNull(w.getLevel(), "漫游不改等级");
        assertTrue(newly.stream().anyMatch(b -> "STREAK_14".equals(b.getBadgeCode())));
    }

    @Test
    void walkTwiceOnTheSameDayWritesNothing() {
        when(growthMapper.selectByMemberForUpdate(1L)).thenReturn(row(5, LocalDate.now(), 0));
        assertTrue(svc.onActiveDay(1L).isEmpty());
        verify(growthMapper, never()).updatePlayerGrowth(any());
    }

    @Test
    void thirtyDayStreakAwardsTheTopOfTheLadder() {
        when(growthMapper.selectByMemberForUpdate(1L)).thenReturn(row(29, LocalDate.now().minusDays(1), 0));
        List<PlayerBadge> newly = svc.onActiveDay(1L);
        assertTrue(newly.stream().anyMatch(b -> "STREAK_30".equals(b.getBadgeCode())));
        assertTrue(newly.stream().anyMatch(b -> "STREAK_3".equals(b.getBadgeCode())));
    }

    @Test
    void streakSummaryReportsFreezeAndRiskState() {
        when(growthMapper.selectByMember(1L)).thenReturn(row(6, LocalDate.now().minusDays(2), 1));
        Map<String, Object> s = svc.streakSummary(1L);
        assertEquals(6, s.get("currentStreak"));
        assertEquals(Boolean.TRUE, s.get("streakAtRisk"));
        assertEquals(Boolean.FALSE, s.get("streakActiveToday"));
        assertEquals(0, s.get("streakFreezes"));
        assertEquals(1, s.get("streakFreezesPending"));
        assertEquals(2, s.get("streakFreezeMax"));
        assertEquals(7, s.get("streakFreezeEveryDays"));
        assertEquals(7, s.get("nextStreakBadgeDays"));
        assertEquals(500, s.get("walkMinMeters"));
        assertArrayEquals(new int[] { 3, 7, 14, 30 }, (int[]) s.get("streakLadder"));
    }

    @Test
    void streakSummaryForNewPlayerIsZeroAndQuiet() {
        Map<String, Object> s = svc.streakSummary(42L);
        assertEquals(0, s.get("currentStreak"));
        assertEquals(Boolean.FALSE, s.get("streakAtRisk"));
        assertEquals(3, s.get("nextStreakBadgeDays"));
    }
}
