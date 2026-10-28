package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.util.PuzzleScorePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
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

    @BeforeEach
    void setUp()
    {
        ICmsMemberTemplateService templateService = mock(ICmsMemberTemplateService.class);
        attemptLogMapper = mock(PlayAttemptLogMapper.class);
        progressMapper = mock(PlayerNodeProgressMapper.class);
        UmsMemberMapper memberMapper = mock(UmsMemberMapper.class);
        service = new PuzzlePlayServiceImpl(templateService, attemptLogMapper, progressMapper, memberMapper,
                mock(com.chengyinhub.business.service.ICmsTopicNodeService.class),
                mock(com.chengyinhub.business.service.support.PlayNodeAccessService.class),
                mock(com.chengyinhub.business.service.ICmsPointsSettingService.class),
                mock(com.chengyinhub.business.service.IUmsMemberService.class),
                mock(com.chengyinhub.business.service.IUmsMemberPointsDetailService.class));
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
        p.setStatus(status);
        p.setCompletionMode(completionMode);
        return p;
    }

    @Test
    void arrivalOnlyRowDoesNotBlockReveal()
    {
        when(progressMapper.selectPlayerNodeProgressList(any()))
                .thenReturn(Collections.singletonList(row(0, null)));

        service.recordReveal(MEMBER, TOPIC, NODE, ACTIVITY);

        verify(attemptLogMapper).insertPlayAttemptLog(any());
    }

    @Test
    void originalRunProbeDoesNotUseCompletionFromAnotherRun() {
        when(progressMapper.selectPlayerNodeProgressList(any())).thenAnswer(call -> {
            PlayerNodeProgress probe = call.getArgument(0);
            org.junit.jupiter.api.Assertions.assertEquals("journey:102", probe.getPlayInstanceId());
            org.junit.jupiter.api.Assertions.assertEquals(MEMBER, probe.getMemberId());
            org.junit.jupiter.api.Assertions.assertEquals(TOPIC, probe.getTopicId());
            org.junit.jupiter.api.Assertions.assertEquals(ACTIVITY, probe.getActivityId());
            return Collections.emptyList();
        });
        service.recordReveal(MEMBER, TOPIC, NODE, ACTIVITY, "journey:102");
        verify(attemptLogMapper).insertPlayAttemptLog(any());
    }

    @Test
    void completedRowStillBlocksReveal()
    {
        when(progressMapper.selectPlayerNodeProgressList(any()))
                .thenReturn(Collections.singletonList(row(1, null)));

        assertThrows(IllegalStateException.class,
                () -> service.recordReveal(MEMBER, TOPIC, NODE, ACTIVITY));
        verify(attemptLogMapper, never()).insertPlayAttemptLog(any());
    }

    @Test
    void alreadyRevealedCompletionStaysIdempotent()
    {
        when(progressMapper.selectPlayerNodeProgressList(any()))
                .thenReturn(Collections.singletonList(row(1, PuzzleScorePolicy.COMPLETION_REVEALED)));

        service.recordReveal(MEMBER, TOPIC, NODE, ACTIVITY);

        verify(attemptLogMapper, never()).insertPlayAttemptLog(any());
    }
}
