package com.chengyinhub.business.service.impl;

import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.vo.LeaderboardItemVO;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.service.IPlayerNodeProgressService;

/**
 * 玩家节点打卡进度 Service 实现
 *
 * @author cy
 * @date 2026-06-19
 */
@Service
public class PlayerNodeProgressServiceImpl implements IPlayerNodeProgressService
{
    @Autowired
    private PlayerNodeProgressMapper playerNodeProgressMapper;

    @Autowired(required = false)
    private com.chengyinhub.business.mapper.TopicRouteSessionMapper routeSessions;

    private void instance(PlayerNodeProgress p) {
        if (p.getPlayInstanceId() != null || p.getMemberId() == null || p.getTopicId() == null || p.getActivityId() == null) return;
        com.chengyinhub.business.domain.TopicRouteSession current = routeSessions == null ? null : routeSessions.selectCurrentByOwner(p.getActivityId(),p.getTopicId(),p.getMemberId());
        p.setPlayInstanceId(current == null ? "default" : current.getPlayInstanceId());
    }

    @Override
    public List<PlayerNodeProgress> selectPlayerNodeProgressList(PlayerNodeProgress playerNodeProgress)
    {
        instance(playerNodeProgress);
        return playerNodeProgressMapper.selectPlayerNodeProgressList(playerNodeProgress);
    }

    @Override
    public PlayerNodeProgress selectPlayerNodeProgressById(Long id)
    {
        return playerNodeProgressMapper.selectPlayerNodeProgressById(id);
    }

    @Override
    public Long countPlayersByTopicId(Long topicId)
    {
        return playerNodeProgressMapper.countPlayersByTopicId(topicId);
    }

    @Override
    public int insertPlayerNodeProgress(PlayerNodeProgress playerNodeProgress)
    {
        instance(playerNodeProgress);
        if (playerNodeProgress.getPlayInstanceId() == null) playerNodeProgress.setPlayInstanceId("default");
        return playerNodeProgressMapper.insertPlayerNodeProgress(playerNodeProgress);
    }

    @Override
    public int markPhotoMachineApproved(Long id)
    {
        return playerNodeProgressMapper.markPhotoMachineApproved(id);
    }

    @Override
    public Integer selectPuzzlePersonalBest(Long memberId, Long topicId)
    {
        return playerNodeProgressMapper.selectPuzzlePersonalBest(memberId, topicId);
    }

    @Override
    public List<LeaderboardItemVO> selectActivityLeaderboard(Long activityId, Long topicId, int limit)
    {
        return playerNodeProgressMapper.selectActivityLeaderboard(activityId, topicId, limit);
    }

    @Override
    public List<LeaderboardItemVO> selectRouteLeaderboard(Long activityId, Long topicId, int limit)
    {
        return playerNodeProgressMapper.selectRouteLeaderboard(activityId, topicId, limit);
    }

    @Override
    public LeaderboardItemVO selectRouteLeaderboardRow(Long activityId, Long topicId, Long memberId)
    {
        return playerNodeProgressMapper.selectRouteLeaderboardRow(activityId, topicId, memberId);
    }
}
