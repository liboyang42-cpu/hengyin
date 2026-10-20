package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.service.ITopicRouteRuntimeService;
import com.chengyinhub.business.domain.CmsTopic;
import com.chengyinhub.business.domain.TopicRouteSession;
import com.chengyinhub.business.mapper.TopicRouteSessionMapper;
import com.chengyinhub.business.mapper.CmsTopicMapper;
import com.chengyinhub.business.mapper.CmsTopicNodeMapper;
import com.chengyinhub.business.mapper.CmsActivityMapper;
import com.chengyinhub.business.mapper.CmsRegistrationMapper;
import com.chengyinhub.business.util.PuzzleScorePolicy;
import com.chengyinhub.common.exception.ServiceException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Objects;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 谜题答案揭示的完成判定(R10-09 收尾):仅到店 status=0 行不是「该节点已完成」,
 * 不能挡玩家在 mode2 到店后揭示答案;真完成(status=1)才按既有合同拒绝/幂等。
 */
class PuzzleRevealArrivalFactTest
{
    private static final long MEMBER = 7L;
    private static final long TOPIC = 9L;
    private static final long NODE = 11L;
    private static final long ACTIVITY = 0L;

    private PlayAttemptLogMapper attemptLogMapper;
    private PlayerNodeProgressMapper progressMapper;
    private PuzzlePlayServiceImpl service;
    private ITopicRouteRuntimeService runtime;

    @BeforeEach
    void setUp()
    {
        ICmsMemberTemplateService templateService = mock(ICmsMemberTemplateService.class);
        attemptLogMapper = mock(PlayAttemptLogMapper.class);
        progressMapper = mock(PlayerNodeProgressMapper.class);
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        service = new PuzzlePlayServiceImpl(templateService, attemptLogMapper, progressMapper, memberMapper);
        runtime = mock(ITopicRouteRuntimeService.class);
        ReflectionTestUtils.setField(service, "routeRuntimeService", runtime);
        when(runtime.playInstance(MEMBER, TOPIC, ACTIVITY)).thenReturn("default");
        when(runtime.assertCurrentPlayInstance(MEMBER, TOPIC, ACTIVITY, "default")).thenReturn(false);
        when(memberMapper.lockMemberById(MEMBER)).thenReturn(MEMBER);
        when(attemptLogMapper.insertPlayAttemptLog(any())).thenReturn(1);
    }

    private PlayerNodeProgress row(Integer status, String completionMode)
    {
        PlayerNodeProgress p = new PlayerNodeProgress();
        p.setMemberId(MEMBER);
        p.setTopicId(TOPIC);
        p.setNodeId(NODE);
        p.setActivityId(ACTIVITY);
        p.setPlayInstanceId("default");
        p.setStatus(status);
        p.setCompletionMode(completionMode);
        return p;
    }

    @Test
    void arrivalOnlyRowDoesNotBlockReveal()
    {
        when(progressMapper.selectPlayerNodeProgressListForInstance(argThat(this::exactProbe), eq("default")))
                .thenReturn(Collections.singletonList(row(0, null)));

        service.recordReveal(MEMBER, TOPIC, NODE, ACTIVITY);

        verify(attemptLogMapper).insertPlayAttemptLog(any());
        verify(runtime).assertCurrentPlayInstance(MEMBER, TOPIC, ACTIVITY, "default");
        verify(progressMapper).selectPlayerNodeProgressListForInstance(argThat(this::exactProbe), eq("default"));
    }

    @Test
    void completedRowStillBlocksReveal()
    {
        when(progressMapper.selectPlayerNodeProgressListForInstance(argThat(this::exactProbe), eq("default")))
                .thenReturn(Collections.singletonList(row(1, null)));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.recordReveal(MEMBER, TOPIC, NODE, ACTIVITY));
        assertEquals("该节点已完成", failure.getMessage());
        verify(progressMapper).selectPlayerNodeProgressListForInstance(argThat(this::exactProbe), eq("default"));
        verify(attemptLogMapper, never()).insertPlayAttemptLog(any());
    }

    @Test
    void alreadyRevealedCompletionStaysIdempotent()
    {
        when(progressMapper.selectPlayerNodeProgressListForInstance(argThat(this::exactProbe), eq("default")))
                .thenReturn(Collections.singletonList(row(1, PuzzleScorePolicy.COMPLETION_REVEALED)));

        service.recordReveal(MEMBER, TOPIC, NODE, ACTIVITY);

        verify(attemptLogMapper, never()).insertPlayAttemptLog(any());
        verify(progressMapper).selectPlayerNodeProgressListForInstance(argThat(this::exactProbe), eq("default"));
    }

    private boolean exactProbe(PlayerNodeProgress probe) {
        return probe != null && Objects.equals(MEMBER, probe.getMemberId())
                && Objects.equals(TOPIC, probe.getTopicId()) && Objects.equals(ACTIVITY, probe.getActivityId())
                && Objects.equals(NODE, probe.getNodeId());
    }

    @Test
    void missingRuntimeIsAnInfrastructureFailureWithoutWritingReveal() {
        ReflectionTestUtils.setField(service, "routeRuntimeService", null);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.recordReveal(MEMBER, TOPIC, NODE, ACTIVITY));
        assertEquals("路线运行时不可用", failure.getMessage());
        verify(attemptLogMapper, never()).insertPlayAttemptLog(any());
    }

    @Test
    void anotherOwnersDefaultCannotSupplyTheMissingCurrentInstance() {
        when(runtime.playInstance(MEMBER, TOPIC, ACTIVITY)).thenReturn(null);
        when(runtime.playInstance(MEMBER + 1, TOPIC, ACTIVITY)).thenReturn("default");
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.recordReveal(MEMBER, TOPIC, NODE, ACTIVITY));
        assertEquals("本局不可读取", failure.getMessage());
        verify(runtime, never()).playInstance(MEMBER + 1, TOPIC, ACTIVITY);
        verify(attemptLogMapper, never()).insertPlayAttemptLog(any());
    }

    @Test
    void staleCurrentInstanceCannotReadOldRowsOrWriteReveal() {
        when(runtime.assertCurrentPlayInstance(MEMBER, TOPIC, ACTIVITY, "default"))
                .thenThrow(new ServiceException("本局已更新，请刷新", 409));
        assertThrows(ServiceException.class, () -> service.recordReveal(MEMBER, TOPIC, NODE, ACTIVITY));
        verify(progressMapper, never()).selectPlayerNodeProgressListForInstance(argThat(this::exactProbe), eq("default"));
        verify(attemptLogMapper, never()).insertPlayAttemptLog(any());
    }

    @Test
    void nonThemeCannotAcceptANonDefaultNamespace() {
        when(runtime.playInstance(MEMBER, TOPIC, ACTIVITY)).thenReturn("replay:42");
        when(runtime.assertCurrentPlayInstance(MEMBER, TOPIC, ACTIVITY, "replay:42")).thenReturn(false);
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> service.recordReveal(MEMBER, TOPIC, NODE, ACTIVITY));
        assertEquals("本局不可读取", failure.getMessage());
        verify(attemptLogMapper, never()).insertPlayAttemptLog(any());
    }

    @Test
    void realNonThemeRuntimeWithoutRouteKeepsArrivalOnlyRevealLegal() {
        TopicRouteSessionMapper routes = mock(TopicRouteSessionMapper.class);
        CmsTopicMapper topics = mock(CmsTopicMapper.class);
        CmsTopic topic = new CmsTopic(); topic.setId(TOPIC);
        when(topics.selectCmsTopicById(TOPIC)).thenReturn(topic);
        TopicRouteRuntimeServiceImpl real = new TopicRouteRuntimeServiceImpl(routes, topics,
                mock(CmsTopicNodeMapper.class), progressMapper,
                mock(CmsActivityMapper.class), mock(CmsRegistrationMapper.class));
        ReflectionTestUtils.setField(service, "routeRuntimeService", real);
        when(progressMapper.selectPlayerNodeProgressListForInstance(argThat(this::exactProbe), eq("default")))
                .thenReturn(Collections.singletonList(row(0, null)));

        service.recordReveal(MEMBER, TOPIC, NODE, ACTIVITY);

        verify(routes).selectByOwner(ACTIVITY, TOPIC, MEMBER, "default");
        verify(routes).selectByOwnerForUpdate(ACTIVITY, TOPIC, MEMBER, "default");
        verify(routes, never()).insertSession(any(TopicRouteSession.class));
        verify(attemptLogMapper).insertPlayAttemptLog(any());
    }
}
