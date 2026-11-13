package com.chengyinhub.web.service.play;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.chengyinhub.business.service.team.pool.TeamResourcePoolService;

/**
 * 一局的查看者队伍 id（/api/play/nodes 与 /api/play/player-journey/report 的 teamId）。
 * 只读、尽力而为：读不到就不下发，绝不拖垮节点列表或战报，也不猜队伍。
 */
final class PlayRunTeam
{
    private static final Logger log = LoggerFactory.getLogger(PlayRunTeam.class);

    private PlayRunTeam() { }

    static Long of(TeamResourcePoolService teams, Long memberId, Long activityId, Long topicId)
    {
        if (teams == null || memberId == null || memberId <= 0) return null;
        try
        {
            return teams.viewerRunTeam(memberId, activityId == null ? 0L : activityId, topicId == null ? 0L : topicId);
        }
        catch (RuntimeException e)
        {
            log.warn("本局队伍读取失败 member={} activity={} topic={}", memberId, activityId, topicId, e);
            return null;
        }
    }
}
