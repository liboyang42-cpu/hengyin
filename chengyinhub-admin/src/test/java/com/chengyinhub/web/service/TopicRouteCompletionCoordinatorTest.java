package com.chengyinhub.web.service;

import com.chengyinhub.business.domain.route.NodeOutcome;
import com.chengyinhub.business.domain.route.TopicRouteState;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TopicRouteCompletionCoordinatorTest {
    @Test
    void separatesClientActionFromServerReceiptAndUsesTrustedAdvancedOutcome() {
        ITopicRouteRuntimeService runtime = mock(ITopicRouteRuntimeService.class);
        TopicRouteState initial = new TopicRouteState(); initial.setRouteMode(TopicRouteState.BRANCH_GRAPH);
        when(runtime.state(7L, 10L, 20L)).thenReturn(initial);
        when(runtime.advance(anyLong(), anyLong(), anyLong(), anyInt(), anyString(), anyString(), any(NodeOutcome.class)))
                .thenReturn(initial);
        TopicRouteCompletionCoordinator coordinator = new TopicRouteCompletionCoordinator(runtime);

        coordinator.advance(7L, 10L, 20L, 30L, "client-action", 4,
                "player-node-progress:99", "HISTORY_END", "IGNORED_PREFERENCE");

        ArgumentCaptor<NodeOutcome> outcome = ArgumentCaptor.forClass(NodeOutcome.class);
        verify(runtime).advance(eq(7L), eq(10L), eq(20L), eq(4), eq("client-action"),
                eq("ADVANCED_RESULT"), outcome.capture());
        assertEquals("player-node-progress:99", outcome.getValue().getReceiptId());
        assertEquals("HISTORY_END", outcome.getValue().getOutcomeCode());
    }

    @Test
    void branchAdvanceFailsClosedWithoutServerCompletionReceipt() {
        ITopicRouteRuntimeService runtime = mock(ITopicRouteRuntimeService.class);
        TopicRouteState initial = new TopicRouteState(); initial.setRouteMode(TopicRouteState.BRANCH_GRAPH);
        when(runtime.state(anyLong(), anyLong(), anyLong())).thenReturn(initial);
        TopicRouteCompletionCoordinator coordinator = new TopicRouteCompletionCoordinator(runtime);
        assertThrows(ServiceException.class, () -> coordinator.advance(7L, 10L, 20L, 30L,
                "client-action", 0, null, null, null));
    }

    @Test
    void clientValueCannotMasqueradeAsTrustedProgressReceipt() {
        ITopicRouteRuntimeService runtime = mock(ITopicRouteRuntimeService.class);
        TopicRouteState initial = new TopicRouteState(); initial.setRouteMode(TopicRouteState.BRANCH_GRAPH);
        when(runtime.state(anyLong(), anyLong(), anyLong())).thenReturn(initial);
        TopicRouteCompletionCoordinator coordinator = new TopicRouteCompletionCoordinator(runtime);

        assertThrows(ServiceException.class, () -> coordinator.advance(7L, 10L, 20L, 30L,
                "client-action", 0, "client-action", null, null));
    }

    @Test
    void routeVersionConflictCodeIsNotSwallowedByCoordinator() {
        ITopicRouteRuntimeService runtime = mock(ITopicRouteRuntimeService.class);
        TopicRouteState initial = new TopicRouteState(); initial.setRouteMode(TopicRouteState.BRANCH_GRAPH);
        when(runtime.state(anyLong(), anyLong(), anyLong())).thenReturn(initial);
        when(runtime.advance(anyLong(), anyLong(), anyLong(), anyInt(), anyString(), anyString(), any(NodeOutcome.class)))
                .thenThrow(new ServiceException("路线状态已更新，请刷新后重试", HttpStatus.CONFLICT));
        TopicRouteCompletionCoordinator coordinator = new TopicRouteCompletionCoordinator(runtime);

        ServiceException error = assertThrows(ServiceException.class, () -> coordinator.advance(7L, 10L, 20L, 30L,
                "client-action", 4, "player-node-progress:99", null, null));

        assertEquals(Integer.valueOf(HttpStatus.CONFLICT), error.getCode());
        assertEquals("路线状态已更新，请刷新后重试", error.getMessage());
    }

    @Test
    void missingExpectedVersionIsPlainClientErrorWithoutConflictCode() {
        ITopicRouteRuntimeService runtime = mock(ITopicRouteRuntimeService.class);
        TopicRouteState initial = new TopicRouteState(); initial.setRouteMode(TopicRouteState.BRANCH_GRAPH);
        when(runtime.state(anyLong(), anyLong(), anyLong())).thenReturn(initial);
        TopicRouteCompletionCoordinator coordinator = new TopicRouteCompletionCoordinator(runtime);

        ServiceException error = assertThrows(ServiceException.class, () -> coordinator.advance(7L, 10L, 20L, 30L,
                "client-action", null, "player-node-progress:99", null, null));

        assertNull(error.getCode());
        assertEquals("路线完成请求缺少 routeActionId 或 expectedRouteVersion", error.getMessage());
    }

    /** 线性旅程局(sessionId 非空)与分支一样推进本局状态,effects/requires 才落得下去。 */
    @Test
    void linearJourneySessionAdvancesThroughTheSameRuntime() {
        ITopicRouteRuntimeService runtime = mock(ITopicRouteRuntimeService.class);
        TopicRouteState linear = new TopicRouteState();
        linear.setRouteMode(TopicRouteState.LINEAR);
        linear.setSessionId(99L);
        when(runtime.state(7L, 10L, 20L)).thenReturn(linear);
        when(runtime.advance(anyLong(), anyLong(), anyLong(), anyInt(), anyString(), anyString(), any(NodeOutcome.class)))
                .thenReturn(linear);
        TopicRouteCompletionCoordinator coordinator = new TopicRouteCompletionCoordinator(runtime);

        coordinator.advance(7L, 10L, 20L, 30L, "client-action", 2,
                "player-node-progress:99", null, null);

        verify(runtime).advance(eq(7L), eq(10L), eq(20L), eq(2), eq("client-action"),
                eq("CHOICE"), any(NodeOutcome.class));
    }

    /**
     * 线性旅程局不接受缺 actionId 的推进:服务端发起的完成(如拍照审核通过)必须像分支一样
     * 提供可重放 actionId + 期望版本,否则宁可报错也不能静默跳过本局状态写入。
     */
    @Test
    void linearJourneySessionRejectsAdvanceWithoutActionId() {
        ITopicRouteRuntimeService runtime = mock(ITopicRouteRuntimeService.class);
        TopicRouteState linear = new TopicRouteState();
        linear.setRouteMode(TopicRouteState.LINEAR);
        linear.setSessionId(99L);
        when(runtime.state(7L, 10L, 20L)).thenReturn(linear);
        TopicRouteCompletionCoordinator coordinator = new TopicRouteCompletionCoordinator(runtime);

        ServiceException error = assertThrows(ServiceException.class, () -> coordinator.advance(7L, 10L, 20L, 30L,
                null, null, "player-node-progress:99", null, null));

        assertNull(error.getCode());
        verify(runtime, org.mockito.Mockito.never()).advance(anyLong(), anyLong(), anyLong(), anyInt(),
                anyString(), anyString(), any(NodeOutcome.class));
    }

    /** 没有局的老线性主题照旧只回读状态,不碰 advance,也不因缺 routeActionId 报错。 */
    @Test
    void legacyLinearWithoutSessionStaysReadOnly() {
        ITopicRouteRuntimeService runtime = mock(ITopicRouteRuntimeService.class);
        TopicRouteState linear = new TopicRouteState();
        linear.setRouteMode(TopicRouteState.LINEAR);
        when(runtime.state(7L, 10L, 20L)).thenReturn(linear);
        TopicRouteCompletionCoordinator coordinator = new TopicRouteCompletionCoordinator(runtime);

        TopicRouteState result = coordinator.advance(7L, 10L, 20L, 30L, null, null,
                "player-node-progress:99", null, null);

        assertEquals(TopicRouteState.LINEAR, result.getRouteMode());
        verify(runtime, org.mockito.Mockito.never()).advance(anyLong(), anyLong(), anyLong(), anyInt(),
                anyString(), anyString(), any(NodeOutcome.class));
    }

    @Test
    void nonConflictRuntimeErrorIsWrappedWithoutCode() {
        ITopicRouteRuntimeService runtime = mock(ITopicRouteRuntimeService.class);
        TopicRouteState initial = new TopicRouteState(); initial.setRouteMode(TopicRouteState.BRANCH_GRAPH);
        when(runtime.state(anyLong(), anyLong(), anyLong())).thenReturn(initial);
        when(runtime.advance(anyLong(), anyLong(), anyLong(), anyInt(), anyString(), anyString(), any(NodeOutcome.class)))
                .thenThrow(new IllegalStateException("节点当前不可用"));
        TopicRouteCompletionCoordinator coordinator = new TopicRouteCompletionCoordinator(runtime);

        ServiceException error = assertThrows(ServiceException.class, () -> coordinator.advance(7L, 10L, 20L, 30L,
                "client-action", 4, "player-node-progress:99", null, null));

        assertNull(error.getCode());
        assertEquals("节点当前不可用", error.getMessage());
    }

    @Test void originalCompletionNeverResolvesCurrentRun() {
        ITopicRouteRuntimeService runtime = mock(ITopicRouteRuntimeService.class);
        TopicRouteCompletionCoordinator coordinator = new TopicRouteCompletionCoordinator(runtime);
        TopicRouteState result = new TopicRouteState();
        when(runtime.advanceOriginal(eq(7L), eq(10L), eq(20L), eq(71L), eq(4),
                eq("original-action"), eq("CHOICE"), any(NodeOutcome.class))).thenReturn(result);
        assertEquals(result, coordinator.advanceOriginal(7L, 10L, 20L, 30L, 71L,
                "original-action", 4, "player-node-progress:99", null, null));
        verify(runtime, org.mockito.Mockito.never()).state(anyLong(), anyLong(), anyLong());
        verify(runtime, org.mockito.Mockito.never()).advance(anyLong(), anyLong(), anyLong(),
                anyInt(), anyString(), anyString(), any(NodeOutcome.class));
    }

    @Test void originalCompletionPropagatesSettlementFailure() {
        ITopicRouteRuntimeService runtime = mock(ITopicRouteRuntimeService.class);
        TopicRouteCompletionCoordinator coordinator = new TopicRouteCompletionCoordinator(runtime);
        when(runtime.advanceOriginal(anyLong(), anyLong(), anyLong(), anyLong(), anyInt(),
                anyString(), anyString(), any(NodeOutcome.class)))
                .thenThrow(new ServiceException("ORIENT_REVIEW_PENDING", 409));
        ServiceException error = assertThrows(ServiceException.class,
                () -> coordinator.advanceOriginal(7L, 10L, 20L, 30L, 71L,
                        "original-action", 4, "player-node-progress:99", null, null));
        assertEquals(Integer.valueOf(409), error.getCode());
        assertEquals("ORIENT_REVIEW_PENDING", error.getMessage());
    }
}
