package com.chengyinhub.business.mapper;

import java.util.List;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import org.apache.ibatis.annotations.Param;

/**
 * 玩家节点打卡进度 Mapper
 *
 * @author cy
 * @date 2026-06-19
 */
public interface PlayerNodeProgressMapper
{
    List<PlayerNodeProgress> selectPlayerNodeProgressList(PlayerNodeProgress playerNodeProgress);

    /** 按主键取单行。 */
    PlayerNodeProgress selectPlayerNodeProgressById(@Param("id") Long id);

    /** 至少完成过一个节点的主题玩家数。 */
    long countPlayersByTopicId(@Param("topicId") Long topicId);

    int insertPlayerNodeProgress(PlayerNodeProgress playerNodeProgress);

    int updateShopDayPhoto(@Param("id") Long id, @Param("picUrl") String picUrl);

    /**
     * 拍照一律机审(2026-09-17 拍板,人工审核已下线):存量待审行(status=0)0→1 条件更新。
     * 返回 1 = 本次是唯一一次「完成事实成立」;返回 0 = 已完成/并发已处理 ⇒ 调用方不得再发奖。
     * checkin_time 改写为完成时刻(完成时间/名次同分 tie-break 用)。
     */
    int markPhotoMachineApproved(@Param("id") Long id);

    /** 探店日游戏完成:进店行 status 0→1 条件更新,返回行数即「是否首次完成」(并发安全)。 */
    int markShopDayGameDone(@Param("memberId") Long memberId, @Param("activityId") Long activityId,
            @Param("topicId") Long topicId, @Param("nodeId") Long nodeId);

    /** 已完成全部谜题节点的各场次总分中最高值；未形成完整谜题局时返回 null。 */
    Integer selectPuzzlePersonalBest(@Param("memberId") Long memberId, @Param("topicId") Long topicId);

    /** 活动/主题排行榜:完成数(status=1)降序,同数先完成者靠前;score=完成节点数。 */
    List<com.chengyinhub.business.domain.vo.LeaderboardItemVO> selectActivityLeaderboard(
            @Param("activityId") Long activityId, @Param("topicId") Long topicId, @Param("limit") int limit);

    /** D-13 分支主题排行榜:到达结局优先,再按结局用时;activityId=0 为自玩榜。 */
    List<com.chengyinhub.business.domain.vo.LeaderboardItemVO> selectRouteLeaderboard(
            @Param("activityId") Long activityId, @Param("topicId") Long topicId, @Param("limit") int limit);

    /** 分支主题榜外的「我」:本人最新一局;没开过局返回 null。 */
    com.chengyinhub.business.domain.vo.LeaderboardItemVO selectRouteLeaderboardRow(
            @Param("activityId") Long activityId, @Param("topicId") Long topicId, @Param("memberId") Long memberId);
}
