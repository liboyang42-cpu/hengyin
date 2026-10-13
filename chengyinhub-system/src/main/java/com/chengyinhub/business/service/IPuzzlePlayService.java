package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsMemberTemplate;
import com.chengyinhub.business.domain.CmsTopicNode;
import com.chengyinhub.business.domain.vo.PuzzleHintResult;

import java.util.Map;

/** 城市定向谜题的提示、揭示和计分事实。 */
public interface IPuzzlePlayService
{
    CmsMemberTemplate selectPuzzleTemplate(CmsTopicNode node);

    PuzzleHintResult requestHint(Long memberId, Long topicId, CmsTopicNode node, Long activityId, int level);

    Map<Long, Integer> selectHintLevels(Long memberId, Long topicId, Long activityId);

    int solvedScore(Long memberId, Long topicId, Long nodeId, Long activityId);

    String revealText(CmsMemberTemplate template);

    void recordReveal(Long memberId, Long topicId, Long nodeId, Long activityId);
}
