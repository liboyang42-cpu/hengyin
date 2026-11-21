package com.chengyinhub.business.dailyloop;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.chengyinhub.business.domain.GrowthMission;
import com.chengyinhub.business.domain.GrowthMissionProgress;
import com.chengyinhub.business.domain.dto.GrowthMissionCompleteDTO;
import com.chengyinhub.business.mapper.GrowthMissionMapper;
import com.chengyinhub.business.mapper.GrowthMissionProgressMapper;
import com.chengyinhub.business.mapper.MemberGrowthMapper;
import com.chengyinhub.business.service.impl.GrowthServiceImpl;
import com.chengyinhub.common.exception.ServiceException;

/** P7 GRO-06:每日任务只能由系统记,奖励只用 exp_reward 配置,同一天幂等。 */
class GrowthDailyMissionTest {

    private GrowthMissionMapper missions;
    private GrowthMissionProgressMapper progress;
    private MemberGrowthMapper accounts;
    private GrowthServiceImpl svc;

    @BeforeEach
    void setUp() {
        missions = mock(GrowthMissionMapper.class);
        progress = mock(GrowthMissionProgressMapper.class);
        accounts = mock(MemberGrowthMapper.class);
        svc = new GrowthServiceImpl();
        ReflectionTestUtils.setField(svc, "growthMissionMapper", missions);
        ReflectionTestUtils.setField(svc, "growthMissionProgressMapper", progress);
        ReflectionTestUtils.setField(svc, "memberGrowthMapper", accounts);
        when(accounts.updateMemberGrowthReward(any())).thenReturn(1);
        when(accounts.insertEarnedExp(anyLong(), anyInt(), anyString(), any(), any())).thenReturn(1);
    }

    @Test
    void selfReportCannotCompleteAnAutoTrackedDailyMission() {
        when(missions.selectActiveByCode("DAILY_WALK"))
                .thenReturn(DailyMissionBoardReaderTest.mission("DAILY_WALK", "daily_walk", 1, 10));
        GrowthMissionCompleteDTO dto = new GrowthMissionCompleteDTO();
        dto.setMissionCode("DAILY_WALK");
        assertThrows(ServiceException.class, () -> svc.completeMission(3L, dto));
        verify(progress, never()).insertMissionProgress(any());
    }

    @Test
    void completeDailyMissionGrantsConfiguredExpOncePerDayKey() {
        GrowthMission walk = DailyMissionBoardReaderTest.mission("DAILY_WALK", "daily_walk", 1, 15);
        when(progress.insertMissionProgress(any())).thenReturn(1, 0);

        assertEquals(1, svc.completeDailyMission(3L, walk));
        assertEquals(0, svc.completeDailyMission(3L, walk), "insert ignore 撞当日幂等键 = 不再发");

        verify(progress, times(2)).insertMissionProgress(argThat((GrowthMissionProgress p) ->
                "DAILY_WALK".equals(p.getMissionCode()) && "".equals(p.getBizType())
                        && Long.valueOf(0L).equals(p.getBizId()) && Integer.valueOf(15).equals(p.getRewardExp())));
        verify(accounts, times(1)).insertEarnedExp(eq(3L), eq(15), eq("DAILY_WALK"), any(), any());
    }

    @Test
    void zeroRewardRecordsCompletionWithoutExpLedgerRow() {
        GrowthMission reveal = DailyMissionBoardReaderTest.mission("DAILY_REVEAL", "daily_reveal", 20, 0);
        when(progress.insertMissionProgress(any())).thenReturn(1);
        assertEquals(1, svc.completeDailyMission(3L, reveal));
        verify(accounts, never()).insertEarnedExp(anyLong(), anyInt(), anyString(), any(), any());
    }

    @Test
    void nonDailyMissionIsRefused() {
        GrowthMission store = DailyMissionBoardReaderTest.mission("STORE", "store_verify", 1, 10);
        assertEquals(0, svc.completeDailyMission(3L, store));
        assertEquals(0, svc.completeDailyMission(null, store));
        verifyNoInteractions(progress);
    }
}
