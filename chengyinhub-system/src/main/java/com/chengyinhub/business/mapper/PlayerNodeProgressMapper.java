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

    /** 任务已结算后的进度投影，只更新本人的同一局到达行，不发奖。 */
    int markGameplayQuestDone(PlayerNodeProgress scope);

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

    /** F08:记录到达来源;只写第一次(已有来源不覆盖)。 */
    int updateArrivalSource(@Param("id") Long id, @Param("arrivalSource") String arrivalSource);

    /**
     * 自由探索到访(FE-STORY 5.2):本人本局同章节已有到店行的节点写 visit_time / visit_method,只写第一次。
     * 坐标只在原行没有时补写。
     */
    int markVisited(@Param("memberId") Long memberId, @Param("activityId") Long activityId,
                    @Param("topicId") Long topicId, @Param("playInstanceId") String playInstanceId,
                    @Param("nodeIds") java.util.List<Long> nodeIds, @Param("visitMethod") Integer visitMethod,
                    @Param("visitTime") java.util.Date visitTime,
                    @Param("longitude") String longitude, @Param("latitude") String latitude);

    /**
     * FE-STORY 5.5:队友到访过的节点(不含本人)。队友 = 本人在本场/本主题票上的同票成员
     * (购票人 + 已领取席位的受邀人) ∪ 同一 play_team 的已加入成员。只读到店行(checkin_time 不为空)。
     */
    List<Long> selectTeamVisitedNodeIds(@Param("memberId") Long memberId, @Param("activityId") Long activityId,
                                        @Param("topicId") Long topicId);

    /** 本人在本场/本主题到访过的节点(任意一局)。过期后只读剧情用(FE-STORY 7.4)。 */
    List<PlayerNodeProgress> selectOwnVisits(@Param("memberId") Long memberId, @Param("activityId") Long activityId,
                                             @Param("topicId") Long topicId);

    /* CG-ALL-11 本局开局方式(play_run_start_mode)与路线替代起点(cms_topic_alt_station)。 */
    String selectRunStartMode(@org.apache.ibatis.annotations.Param("memberId") Long memberId,
            @org.apache.ibatis.annotations.Param("activityId") Long activityId,
            @org.apache.ibatis.annotations.Param("topicId") Long topicId);

    int upsertRunStartMode(@org.apache.ibatis.annotations.Param("memberId") Long memberId,
            @org.apache.ibatis.annotations.Param("activityId") Long activityId,
            @org.apache.ibatis.annotations.Param("topicId") Long topicId,
            @org.apache.ibatis.annotations.Param("runMode") String runMode,
            @org.apache.ibatis.annotations.Param("altStationId") Long altStationId);

    int deleteRunStartMode(@org.apache.ibatis.annotations.Param("memberId") Long memberId,
            @org.apache.ibatis.annotations.Param("activityId") Long activityId,
            @org.apache.ibatis.annotations.Param("topicId") Long topicId);

    java.util.List<java.util.Map<String, Object>> selectTopicAltStations(
            @org.apache.ibatis.annotations.Param("topicId") Long topicId);
}
