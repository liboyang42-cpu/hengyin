package com.chengyinhub.business.service.impl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.chengyinhub.business.mapper.GameRuntimeSessionMapper;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * 第十二轮 k8(j3 N4 / N3 复走时发现):带状态 / 检定的主题里,首站任务还没开始时到站要先问「这一站解开了没有」,
 * 查任务实例抛 409「请先开始当前任务」;这个只读问句以前把 409 抛给到站,任务又因「尚未到达」开不了,首站死锁。
 */
class SolvedForArrivalNoQuestTest {

    private AdvancedGameRuntimeServiceImpl service(ServiceException fromInstance) {
        AdvancedGameRuntimeServiceImpl service = new AdvancedGameRuntimeServiceImpl();
        GameRuntimeSessionMapper sessions = mock(GameRuntimeSessionMapper.class);
        ITopicRouteRuntimeService route = mock(ITopicRouteRuntimeService.class);
        ReflectionTestUtils.setField(service, "sessionMapper", sessions);
        ReflectionTestUtils.setField(service, "topicRouteRuntimeService", route);
        when(route.genericPlayer(any(), any(), any())).thenReturn(true);
        when(route.gameplaySessionId(any(), any(), any())).thenReturn(5L);
        when(route.zeroChallenge(any(), any(), any(), any())).thenReturn(null);
        when(sessions.selectPlayerSessionsByTopic(any(), any(), any())).thenReturn(java.util.Collections.emptyList());
        when(route.gameplayInstance(any(), any(), any(), any())).thenThrow(fromInstance);
        return service;
    }

    @Test
    void noStartedQuestMeansNotSolvedInsteadOfFailingArrival() {
        assertFalse(service(new ServiceException("请先开始当前任务", 409)).solvedForArrival(7L, 0L, 19L, 29L));
    }

    @Test
    void otherFailuresStillPropagate() {
        assertThrows(ServiceException.class,
                () -> service(new ServiceException("本局读取失败", 500)).solvedForArrival(7L, 0L, 19L, 29L));
    }
}
