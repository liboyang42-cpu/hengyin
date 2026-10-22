package com.chengyinhub.business.service.support;

import com.chengyinhub.business.domain.PlayArriveGuardLog;
import com.chengyinhub.business.mapper.PlayArriveGuardLogMapper;
import com.chengyinhub.system.service.ISysConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** O-01 守卫开关语义:默认 off 不读不写;shadow 只记不拦;enforce 拦截并返回申诉引用号;故障按放行。 */
class ArriveLocationGuardTest
{
    private static final long NOW = 1_800_000_000_000L;
    private static final double LAT = 31.2304;
    private static final double LNG = 121.4737;

    private ISysConfigService config;
    private PlayArriveGuardLogMapper mapper;
    private ArriveLocationGuard guard;

    @BeforeEach
    void setUp()
    {
        config = mock(ISysConfigService.class);
        mapper = mock(PlayArriveGuardLogMapper.class);
        guard = new ArriveLocationGuard()
        {
            @Override
            long nowMillis()
            {
                return NOW;
            }
        };
        ReflectionTestUtils.setField(guard, "configService", config);
        ReflectionTestUtils.setField(guard, "guardLogMapper", mapper);
        when(mapper.selectAcceptedSince(anyLong(), any(), anyInt())).thenReturn(Collections.emptyList());
        doAnswer(inv -> {
            ((PlayArriveGuardLog) inv.getArgument(0)).setId(9001L);
            return 1;
        }).when(mapper).insertGuardLog(any());
    }

    /** 上一站:10 秒前、北边 3km(⇒ 约 288 m/s) */
    private void stubTeleportHistory()
    {
        PlayArriveGuardLog prev = new PlayArriveGuardLog();
        prev.setNodeLat(BigDecimal.valueOf(LAT + 3_000d / 111_195d));
        prev.setNodeLng(BigDecimal.valueOf(LNG));
        prev.setAccuracyM(BigDecimal.TEN);
        prev.setVerdict("PASS");
        prev.setCreatedAt(new Date(NOW - 10_000L));
        when(mapper.selectLastEvaluated(1L)).thenReturn(prev);
        when(mapper.selectLastAccepted(1L)).thenReturn(prev);
    }

    private ArriveLocationGuard.Result check()
    {
        return guard.check(1L, 2L, 3L, 4L, LAT, LNG, 10d);
    }

    @Test
    void defaultsKeepCurrentBehaviourWithoutTouchingTheGuardTable()
    {
        // 键缺失 = off / false
        assertSame(ArriveLocationGuard.PASS, check());
        assertFalse(guard.requireAccuracy());
        verifyNoInteractions(mapper);

        when(config.selectConfigByKey(ArriveLocationGuard.CONFIG_MODE)).thenReturn("off");
        when(config.selectConfigByKey(ArriveLocationGuard.CONFIG_REQUIRE_ACCURACY)).thenReturn("false");
        assertSame(ArriveLocationGuard.PASS, check());
        assertFalse(guard.requireAccuracy());
        verifyNoInteractions(mapper);
    }

    @Test
    void unknownOrBrokenConfigFallsBackToOff()
    {
        when(config.selectConfigByKey(ArriveLocationGuard.CONFIG_MODE)).thenReturn("ENFORCE-ish");
        assertSame(ArriveLocationGuard.PASS, check());
        when(config.selectConfigByKey(any())).thenThrow(new IllegalStateException("redis down"));
        assertSame(ArriveLocationGuard.PASS, check());
        assertFalse(guard.requireAccuracy());
        verifyNoInteractions(mapper);
    }

    @Test
    void requireAccuracyOnlyWhenStrictlyTrue()
    {
        when(config.selectConfigByKey(ArriveLocationGuard.CONFIG_REQUIRE_ACCURACY)).thenReturn(" TRUE ");
        assertTrue(guard.requireAccuracy());
        when(config.selectConfigByKey(ArriveLocationGuard.CONFIG_REQUIRE_ACCURACY)).thenReturn("yes");
        assertFalse(guard.requireAccuracy());
    }

    @Test
    void shadowRecordsWouldBeRejectionButPasses()
    {
        when(config.selectConfigByKey(ArriveLocationGuard.CONFIG_MODE)).thenReturn("shadow");
        stubTeleportHistory();

        assertSame(ArriveLocationGuard.PASS, check());
        ArgumentCaptor<PlayArriveGuardLog> row = ArgumentCaptor.forClass(PlayArriveGuardLog.class);
        verify(mapper).insertGuardLog(row.capture());
        assertEquals("SHADOW", row.getValue().getVerdict());
        assertEquals(ArriveLocationGuardPolicy.SPEED, row.getValue().getReasonCode());
    }

    @Test
    void enforceRejectsWithAppealReference()
    {
        when(config.selectConfigByKey(ArriveLocationGuard.CONFIG_MODE)).thenReturn("enforce");
        stubTeleportHistory();

        ArriveLocationGuard.Result result = check();
        assertTrue(result.rejected());
        assertEquals(ArriveLocationGuardPolicy.SPEED, result.errorCode());
        assertEquals(9001L, result.guardLogId());
        assertTrue(result.appealable());
        ArgumentCaptor<PlayArriveGuardLog> row = ArgumentCaptor.forClass(PlayArriveGuardLog.class);
        verify(mapper).insertGuardLog(row.capture());
        PlayArriveGuardLog saved = row.getValue();
        assertEquals("REJECT", saved.getVerdict());
        assertEquals(1L, saved.getMemberId());
        assertEquals(2L, saved.getActivityId());
        assertEquals(3L, saved.getTopicId());
        assertEquals(4L, saved.getNodeId());
        assertEquals(new Date(NOW), saved.getCreatedAt());
    }

    @Test
    void enforceThrottleIsRetryableNotAppealable()
    {
        when(config.selectConfigByKey(ArriveLocationGuard.CONFIG_MODE)).thenReturn("enforce");
        PlayArriveGuardLog prev = new PlayArriveGuardLog();
        prev.setNodeLat(BigDecimal.valueOf(LAT));
        prev.setNodeLng(BigDecimal.valueOf(LNG));
        prev.setVerdict("PASS");
        prev.setCreatedAt(new Date(NOW - 1_000L));
        when(mapper.selectLastEvaluated(1L)).thenReturn(prev);

        ArriveLocationGuard.Result result = check();
        assertEquals(ArriveLocationGuardPolicy.THROTTLE, result.errorCode());
        assertFalse(result.appealable());
    }

    @Test
    void enforcePassIsRecordedAsAnchor()
    {
        when(config.selectConfigByKey(ArriveLocationGuard.CONFIG_MODE)).thenReturn("enforce");

        assertSame(ArriveLocationGuard.PASS, check());
        ArgumentCaptor<PlayArriveGuardLog> row = ArgumentCaptor.forClass(PlayArriveGuardLog.class);
        verify(mapper).insertGuardLog(row.capture());
        assertEquals("PASS", row.getValue().getVerdict());
        assertNull(row.getValue().getReasonCode());
        assertEquals(0, BigDecimal.TEN.compareTo(row.getValue().getAccuracyM()));
    }

    @Test
    void guardTableFailureFallsBackToPass()
    {
        when(config.selectConfigByKey(ArriveLocationGuard.CONFIG_MODE)).thenReturn("enforce");
        when(mapper.selectAcceptedSince(anyLong(), any(), anyInt()))
                .thenThrow(new RuntimeException("Table 'play_arrive_guard_log' doesn't exist"));

        assertSame(ArriveLocationGuard.PASS, check());
    }
}
