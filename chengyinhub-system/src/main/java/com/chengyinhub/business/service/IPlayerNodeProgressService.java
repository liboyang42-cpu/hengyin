package com.chengyinhub.business.service;

import java.util.List;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.vo.LeaderboardItemVO;

/**
 * 玩家节点打卡进度 Service
 *
 * @author cy
 * @date 2026-06-19
 */
public interface IPlayerNodeProgressService
{
    List<PlayerNodeProgress> selectPlayerNodeProgressList(PlayerNodeProgress playerNodeProgress);

    /** 按主键取单行。 */
    PlayerNodeProgress selectPlayerNodeProgressById(Long id);

    Long countPlayersByTopicId(Long topicId);

    int insertPlayerNodeProgress(PlayerNodeProgress playerNodeProgress);

    /** 拍照一律机审:存量待审行 0→1 条件更新;1=本次为唯一一次完成,0=已完成/并发已处理(不得发奖)。 */
    int markPhotoMachineApproved(Long id);

    Integer selectPuzzlePersonalBest(Long memberId, Long topicId);

    /**
     * 活动/主题排行榜:按完成节点数(status=1)降序,同数先完成者(最后完成时间早)靠前。
     * activityId 与 topicId 二选一;rank 由调用方按列表序补。
     */
    List<LeaderboardItemVO> selectActivityLeaderboard(Long activityId, Long topicId, int limit);
}
