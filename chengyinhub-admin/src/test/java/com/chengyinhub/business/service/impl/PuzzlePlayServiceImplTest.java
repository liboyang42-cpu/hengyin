package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.PlayAttemptLog;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.vo.PuzzleHintResult;
import com.chengyinhub.business.mapper.PlayAttemptLogMapper;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.mapper.UmsMemberMapper;
import com.chengyinhub.business.service.ICmsMemberTemplateService;
import com.chengyinhub.business.util.PuzzleScorePolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class PuzzlePlayServiceImplTest
{
    private ICmsMemberTemplateService templateService;
    private PlayAttemptLogMapper attemptLogMapper;
    private PlayerNodeProgressMapper progressMapper;
    private UmsMemberMapper memberMapper;
    private PuzzlePlayServiceImpl service;
    private CmsTopicNode node;
    private CmsMemberTemplate template;

    @BeforeEach
    void setUp()
    {
        templateService = mock(ICmsMemberTemplateService.class);
        attemptLogMapper = mock(PlayAttemptLogMapper.class);
        progressMapper = mock(PlayerNodeProgressMapper.class);
        memberMapper = mock(UmsMemberMapper.class);
        service = new PuzzlePlayServiceImpl(templateService, attemptLogMapper, progressMapper, memberMapper,
                mock(com.chengyinhub.business.service.ICmsTopicNodeService.class),
                mock(com.chengyinhub.business.service.support.PlayNodeAccessService.class),
                mock(com.chengyinhub.business.service.ICmsPointsSettingService.class),
                mock(com.chengyinhub.business.service.IUmsMemberService.class),
                mock(com.chengyinhub.business.service.IUmsMemberPointsDetailService.class));
        node = new CmsTopicNode();
        node.setId(30L);
        node.setTemplateId(40L);
        template = new CmsMemberTemplate();
        template.setValidationMethod(1);
        when(templateService.selectCmsMemberTemplateById(40L)).thenReturn(template);
        when(memberMapper.lockMemberById(10L)).thenReturn(10L);
    }

    @Test
    void hintTwoOnlyKeepsItsRealTierAndScoresForty()
    {
        template.setHint2("第二级提示");
        when(attemptLogMapper.selectMaxPuzzleHintLevel(10L, 20L, 30L, 0L)).thenReturn(0);
        when(attemptLogMapper.insertPlayAttemptLog(any(PlayAttemptLog.class))).thenReturn(1);

        PuzzleHintResult result = service.requestHint(10L, 20L, node, 0L, 1);

        assertEquals(2, result.getLevel());
        assertEquals(Collections.singletonList("第二级提示"), result.getHints());
        assertEquals(1, result.getHintCount());
        assertEquals(40, result.getScoreCap());
        ArgumentCaptor<PlayAttemptLog> log = ArgumentCaptor.forClass(PlayAttemptLog.class);
        verify(attemptLogMapper).insertPlayAttemptLog(log.capture());
        assertEquals(Integer.valueOf(2), log.getValue().getResult());
    }

    @Test
    void solvedScoreLocksMemberBeforeReadingHintFact()
    {
        when(attemptLogMapper.selectMaxPuzzleHintLevel(10L, 20L, 30L, 0L)).thenReturn(2);

        assertEquals(40, service.solvedScore(10L, 20L, 30L, 0L));

        InOrder order = inOrder(memberMapper, attemptLogMapper);
        order.verify(memberMapper).lockMemberById(10L);
        order.verify(attemptLogMapper).selectMaxPuzzleHintLevel(10L, 20L, 30L, 0L);
    }

    @Test
    void revealRetryDoesNotWriteAnotherAbandonAttempt()
    {
        PlayerNodeProgress progress = new PlayerNodeProgress();
        progress.setCompletionMode(PuzzleScorePolicy.COMPLETION_REVEALED);
        when(progressMapper.selectPlayerNodeProgressList(any(PlayerNodeProgress.class)))
                .thenReturn(Collections.singletonList(progress));

        service.recordReveal(10L, 20L, 30L, 0L);

        verify(attemptLogMapper, never()).insertPlayAttemptLog(any(PlayAttemptLog.class));
    }
}
