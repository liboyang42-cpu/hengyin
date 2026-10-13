package com.chengyinhub.business.service.impl;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.RoamSession;
import com.chengyinhub.business.mapper.PlayerNodeProgressMapper;
import com.chengyinhub.business.mapper.RoamMapper;

/**
 * NPC 记忆拼装: 读 player_node_progress 进度 -> 结构化记忆片段喂入 system prompt。
 *
 * @author chengyinhub
 */
@Service
public class NpcMemoryBuilder
{
    private static final Logger log = LoggerFactory.getLogger(NpcMemoryBuilder.class);

    @Autowired(required = false)
    private PlayerNodeProgressMapper playProgressMapper;

    @Autowired(required = false)
    private RoamMapper roamMapper;

    /**
     * 拼装单活动内记忆片段。
     */
    public String build(Long userId, Long activityId)
    {
        if (playProgressMapper == null || activityId == null)
        {
            return "";
        }
        try
        {
            PlayerNodeProgress query = new PlayerNodeProgress();
            query.setMemberId(userId);
            query.setActivityId(activityId);
            List<PlayerNodeProgress> nodes = playProgressMapper.selectPlayerNodeProgressList(query);
            if (nodes == null || nodes.isEmpty())
            {
                return "";
            }

            int completed = 0;
            int total = nodes.size();
            for (PlayerNodeProgress n : nodes)
            {
                Integer status = n.getStatus();
                if (status != null && status >= 2)
                {
                    completed++; // status >= 2 视为已完成
                }
            }
            return "[玩家进度] 已完成" + completed + "/" + total + "站";
        }
        catch (Exception e)
        {
            log.debug("[NpcMemory] build failed, silent degrade", e);
            return "";
        }
    }

    /**
     * 漫游 Chat 仅给模型当前会话的聚合进度，不带定位、POI 名称、openid 或历史对话。
     */
    public String buildRoam(RoamSession session)
    {
        if (roamMapper == null || session == null || session.getId() == null)
        {
            return "";
        }
        try
        {
            int tiles = roamMapper.countTilesBySession(session.getId());
            int pois = roamMapper.countPoisBySession(session.getId());
            return "[漫游进度] 本次已探索 " + tiles + " 个唯一格子，首次点亮 " + pois
                    + " 个地点，当前处于第一里程碑。";
        }
        catch (Exception e)
        {
            log.debug("[NpcMemory] buildRoam failed, silent degrade", e);
            return "";
        }
    }
}
