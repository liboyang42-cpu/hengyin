package com.chengyinhub.business.dailyloop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.domain.GrowthMission;
import com.chengyinhub.business.domain.vo.DailyMissionBoardVO;
import com.chengyinhub.business.mapper.DailyLoopMapper;
import com.chengyinhub.business.mapper.GrowthMissionMapper;
import com.chengyinhub.business.service.support.DailyMissionBoardReader;

/** P7 GRO-06:今日任务板只列 daily_* 任务,进度来自事实表并封顶,奖励原样取 exp_reward。 */
class DailyMissionBoardReaderTest {

    private GrowthMissionMapper missions;
    private DailyLoopMapper facts;
    private DailyMissionBoardReader reader;

    static GrowthMission mission(String code, String event, Integer target, Integer exp) {
        GrowthMission m = new GrowthMission();
        m.setMissionCode(code); m.setMissionName(code); m.setTriggerEvent(event);
        m.setTargetCount(target); m.setExpReward(exp); m.setStatus("0");
        return m;
    }

    @BeforeEach
    void setUp() {
        missions = mock(GrowthMissionMapper.class);
        facts = mock(DailyLoopMapper.class);
        reader = new DailyMissionBoardReader();
        ReflectionTestUtils.setField(reader, "growthMissionMapper", missions);
        ReflectionTestUtils.setField(reader, "dailyLoopMapper", facts);
    }

    @Test
    void boardListsOnlyAutoTrackedMissionsWithCappedProgress() {
        when(missions.selectActiveMissionList()).thenReturn(Arrays.asList(
                mission("DAILY_WALK", "daily_walk", 1, 10),
                mission("SIGN_IN", "manual_signin", 1, 5),
                mission("DAILY_REVEAL", "daily_reveal", 20, null),
                mission("DAILY_PLAY", "daily_play", null, 0),
                mission("DAILY_SHOP", "daily_shop", 1, 3)));
        when(facts.countQualifyingWalksToday(eq(9L), anyInt(), anyInt())).thenReturn(0);
        when(facts.countRevealedCellsToday(9L)).thenReturn(57);
        when(facts.countNodesPlayedToday(9L)).thenReturn(0);
        when(facts.countShopsVisitedToday(9L)).thenReturn(1);
        when(facts.selectMissionCodesCompletedToday(9L)).thenReturn(Collections.singletonList("DAILY_SHOP"));

        DailyMissionBoardVO board = reader.board(9L);

        assertEquals(4, board.getMissions().size(), "非 daily_* 任务不进今日任务板");
        DailyMissionBoardVO.Item reveal = board.getMissions().get(1);
        assertEquals("daily_reveal", reveal.getEvent());
        assertEquals(20, reveal.getTarget());
        assertEquals(20, reveal.getProgress(), "进度封顶到目标");
        assertFalse(reveal.isCompleted(), "完成以记录为准,不在读里补记");
        assertEquals(0, reveal.getExpReward());
        assertEquals(1, board.getMissions().get(2).getTarget(), "目标缺省为 1");
        DailyMissionBoardVO.Item shop = board.getMissions().get(3);
        assertTrue(shop.isCompleted());
        assertEquals(3, shop.getExpReward());
        assertEquals(1, board.getCompletedCount());
        assertFalse(board.isAllDone());
        assertNotNull(board.getDate());
    }

    @Test
    void emptyConfigurationGivesEmptyBoardWithoutReadingFacts() {
        when(missions.selectActiveMissionList()).thenReturn(Collections.emptyList());
        DailyMissionBoardVO board = reader.board(9L);
        assertTrue(board.getMissions().isEmpty());
        assertFalse(board.isAllDone());
        verifyNoInteractions(facts);
        assertTrue(reader.board(null).getMissions().isEmpty());
    }

    @Test
    void autoTrackedEventsAreRecognised() {
        assertTrue(DailyMissionBoardReader.isAutoTracked("daily_walk"));
        assertTrue(DailyMissionBoardReader.isAutoTracked(" DAILY_SHOP "));
        assertFalse(DailyMissionBoardReader.isAutoTracked("store_verify"));
        assertFalse(DailyMissionBoardReader.isAutoTracked(null));
    }
}
