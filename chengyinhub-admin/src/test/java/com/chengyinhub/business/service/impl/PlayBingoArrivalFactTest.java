package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.service.support.BingoCells;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 宾果进度事实(R10-09 收尾):节点完成进度只认 status==1 正式完成行。
 * 到店/凭证 status=0 行不得点亮格子/解锁档位(否则会在某次节点完成时提前发宾果探索值)。
 */
class PlayBingoArrivalFactTest
{
    private PlayerNodeProgressMapper progressMapper;
    private PlayBingoServiceImpl service;

    @BeforeEach
    void setUp()
    {
        progressMapper = mock(PlayerNodeProgressMapper.class);
        service = new PlayBingoServiceImpl();
        ReflectionTestUtils.setField(service, "progressMapper", progressMapper);
    }

    private PlayerNodeProgress row(long nodeId, Integer status)
    {
        PlayerNodeProgress p = new PlayerNodeProgress();
        p.setMemberId(7L);
        p.setTopicId(9L);
        p.setNodeId(nodeId);
        p.setStatus(status);
        p.setCheckinTime(new Date());
        return p;
    }

    @SuppressWarnings("unchecked")
    private Set<Long> loadedNodeIds()
    {
        BingoCells.Progress progress = ReflectionTestUtils.invokeMethod(service, "loadProgress", 7L, 9L);
        return (Set<Long>) ReflectionTestUtils.getField(progress, "doneNodeIds");
    }

    @Test
    void arrivalOnlyRowDoesNotLightCells()
    {
        when(progressMapper.selectPlayerNodeProgressList(any())).thenReturn(Arrays.asList(
                row(11L, 1),
                row(12L, 0)));

        assertEquals(Collections.singleton(11L), loadedNodeIds(),
                "到店(status=0)行不得进入宾果完成进度");
    }

    @Test
    void completedRowsStillLightCells()
    {
        when(progressMapper.selectPlayerNodeProgressList(any())).thenReturn(Arrays.asList(
                row(11L, 1),
                row(12L, 1)));

        assertEquals(new java.util.HashSet<>(Arrays.asList(11L, 12L)), loadedNodeIds(),
                "真完成行照旧全部计入(修复不得误伤)");
    }
}
