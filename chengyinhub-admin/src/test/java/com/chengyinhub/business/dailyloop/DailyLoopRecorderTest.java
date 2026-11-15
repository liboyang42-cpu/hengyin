package com.chengyinhub.business.dailyloop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.domain.GrowthMission;
import com.chengyinhub.business.domain.PlayerBadge;
import com.chengyinhub.business.mapper.DailyLoopMapper;
import com.chengyinhub.business.mapper.GrowthMissionMapper;
import com.chengyinhub.business.service.IGrowthService;
import com.chengyinhub.business.service.IPlayerProgressionService;
import com.chengyinhub.business.service.impl.DailyLoopRecorder;
import com.chengyinhub.business.service.support.DailyLoopSettings;
import com.chengyinhub.business.service.support.DailyMissionBoardReader;
import com.chengyinhub.system.service.ISysConfigService;

/** P7:真实事件 → 连续天数 / 每日任务。没有事务同步时同步执行;出错只吞掉,不外抛。 */
class DailyLoopRecorderTest {

    private GrowthMissionMapper missions;
    private DailyLoopMapper facts;
    private IGrowthService growth;
    private IPlayerProgressionService progression;
    private DailyLoopRecorder recorder;

    @BeforeEach
    void setUp() {
        missions = mock(GrowthMissionMapper.class);
        facts = mock(DailyLoopMapper.class);
        growth = mock(IGrowthService.class);
        progression = mock(IPlayerProgressionService.class);
        ISysConfigService cfg = mock(ISysConfigService.class);
        when(cfg.selectConfigByKey(anyString())).thenReturn("");
        DailyLoopSettings settings = new DailyLoopSettings(cfg);

        DailyMissionBoardReader reader = new DailyMissionBoardReader();
        ReflectionTestUtils.setField(reader, "growthMissionMapper", missions);
        ReflectionTestUtils.setField(reader, "dailyLoopMapper", facts);
        ReflectionTestUtils.setField(reader, "settings", settings);

        recorder = new DailyLoopRecorder();
        ReflectionTestUtils.setField(recorder, "boardReader", reader);
        ReflectionTestUtils.setField(recorder, "growthMissionMapper", missions);
        ReflectionTestUtils.setField(recorder, "growthService", growth);
        ReflectionTestUtils.setField(recorder, "progressionService", progression);
        ReflectionTestUtils.setField(recorder, "settings", settings);
        when(progression.streakSummary(anyLong())).thenReturn(new HashMap<>(Map.of("currentStreak", 4)));
        when(facts.selectMissionCodesCompletedToday(anyLong())).thenReturn(Collections.emptyList());
    }

    @Test
    void qualifyingWalkAdvancesStreakAndCompletesWalkMission() {
        GrowthMission walk = DailyMissionBoardReaderTest.mission("DAILY_WALK", "daily_walk", 1, 10);
        when(missions.selectActiveByEvent("daily_walk")).thenReturn(Collections.singletonList(walk));
        when(facts.countQualifyingWalksToday(7L, 500, 10)).thenReturn(1);
        when(growth.completeDailyMission(7L, walk)).thenReturn(1);
        PlayerBadge b = new PlayerBadge(); b.setBadgeCode("STREAK_3");
        when(progression.onActiveDay(7L)).thenReturn(Collections.singletonList(b));

        AtomicReference<Map<String, Object>> receipt = new AtomicReference<>();
        recorder.afterWalkFinished(7L, 800, 3, receipt::set);

        verify(progression).onActiveDay(7L);
        verify(growth).completeDailyMission(7L, walk);
        assertEquals(Boolean.TRUE, receipt.get().get("qualified"));
        assertEquals(Collections.singletonList("STREAK_3"), receipt.get().get("newBadges"));
        assertEquals(1, receipt.get().get("missionsCompleted"));
        assertEquals(4, receipt.get().get("currentStreak"));
    }

    @Test
    void shortWalkDoesNotTouchStreak() {
        AtomicReference<Map<String, Object>> receipt = new AtomicReference<>();
        recorder.afterWalkFinished(7L, 100, 2, receipt::set);
        verify(progression, never()).onActiveDay(anyLong());
        assertEquals(Boolean.FALSE, receipt.get().get("qualified"));
    }

    @Test
    void revealCompletesOnlyWhenTodayCountReachesTarget() {
        GrowthMission reveal = DailyMissionBoardReaderTest.mission("DAILY_REVEAL", "daily_reveal", 20, 0);
        when(missions.selectActiveByEvent("daily_reveal")).thenReturn(Collections.singletonList(reveal));
        when(facts.countRevealedCellsToday(7L)).thenReturn(19, 20);

        recorder.afterCellsRevealed(7L, 5);
        verify(growth, never()).completeDailyMission(anyLong(), any());
        recorder.afterCellsRevealed(7L, 1);
        verify(growth).completeDailyMission(7L, reveal);
    }

    @Test
    void alreadyCompletedMissionSkipsCountingAndWriting() {
        GrowthMission reveal = DailyMissionBoardReaderTest.mission("DAILY_REVEAL", "daily_reveal", 20, 0);
        when(missions.selectActiveByEvent("daily_reveal")).thenReturn(Collections.singletonList(reveal));
        when(facts.selectMissionCodesCompletedToday(7L)).thenReturn(Arrays.asList("DAILY_REVEAL"));
        recorder.afterCellsRevealed(7L, 5);
        verify(facts, never()).countRevealedCellsToday(anyLong());
        verify(growth, never()).completeDailyMission(anyLong(), any());
    }

    @Test
    void zeroRevealAndUnrecordedShopVisitDoNothing() {
        recorder.afterCellsRevealed(7L, 0);
        recorder.afterShopVisited(7L, false);
        recorder.afterNodePlayed(0L);
        verifyNoInteractions(missions, growth);
    }

    @Test
    void failuresAreSwallowedSoTheCallerNeverRollsBack() {
        when(missions.selectActiveByEvent("daily_play")).thenThrow(new IllegalStateException("db"));
        assertDoesNotThrow(() -> recorder.afterNodePlayed(7L));
    }

    @Test
    void shopVisitCompletesShopMission() {
        GrowthMission shop = DailyMissionBoardReaderTest.mission("DAILY_SHOP", "daily_shop", 1, 3);
        when(missions.selectActiveByEvent("daily_shop")).thenReturn(Collections.singletonList(shop));
        when(facts.countShopsVisitedToday(7L)).thenReturn(1);
        recorder.afterShopVisited(7L, true);
        verify(growth).completeDailyMission(7L, shop);
    }
}
