package com.chengyinhub.business.mapper;

import java.util.List;
import java.util.Date;
import com.chengyinhub.business.domain.PlayerNodeProgress;
import com.chengyinhub.business.domain.vo.LeaderboardItemVO;
import org.apache.ibatis.annotations.Param;

/**
 * 玩家节点打卡进度 Mapper
 *
 * @author cy
 * @date 2026-06-19
 */
public interface PlayerNodeProgressMapper
{
    /** Negative award evidence only. Preserve the original member/activity/node opportunity. */
    java.util.Map<String,Object> selectOriginalNodeAwardEvidence(@Param("memberId") Long memberId,
            @Param("activityId") Long activityId, @Param("topicId") Long topicId, @Param("nodeId") Long nodeId);

    /** Prefix only enumerates candidates; Java must validate each persisted FIRST origin. */
    List<com.chengyinhub.business.domain.TopicRouteSession> selectOriginalInstanceAwardSessions(
            @Param("memberId") Long memberId, @Param("activityId") Long activityId, @Param("nodeId") Long nodeId);
    /** 旧入口仅查询 default 局；不从路由表猜测当前重玩局。 */
    List<PlayerNodeProgress> selectPlayerNodeProgressList(PlayerNodeProgress playerNodeProgress);

    /** 调用方必须先从受保护的路线运行时取得本局标识。 */
    List<PlayerNodeProgress> selectPlayerNodeProgressListForInstance(
            @Param("probe") PlayerNodeProgress playerNodeProgress,
            @Param("playInstanceId") String playInstanceId);

    /** Arrival sharing preserves existing evidence, including soft-deleted rows, in this exact scope only. */
    int countPlayerNodeProgressForInstanceIncludingDeleted(@Param("memberId") Long memberId,
            @Param("activityId") Long activityId, @Param("topicId") Long topicId,
            @Param("nodeId") Long nodeId, @Param("playInstanceId") String playInstanceId);

    /** 仅枚举归属三元组；不在 SQL 中决定主题当前局，不读取调用者指定的实例。 */
    List<PlayerNodeProgress> selectProgressScopes(@Param("probe") PlayerNodeProgress probe);

    /** scopes 的每个实例必须由 Java 路线运行时校验；空集合必须返回空。 */
    List<PlayerNodeProgress> selectProgressForResolvedScopes(
            @Param("scopes") List<PlayerNodeProgress> scopes, @Param("nodeId") Long nodeId);

    /** 单个活动/主题范围内的有界归属集合；结果只供服务排序，不直接对外返回。 */
    List<LeaderboardScopeScore> selectLeaderboardForResolvedScopes(
            @Param("activityId") Long activityId, @Param("topicId") Long topicId,
            @Param("scopes") List<PlayerNodeProgress> scopes);

    class LeaderboardScopeScore extends LeaderboardItemVO {
        private Date lastCheckinTime;
        private Long activityId;
        private Long topicId;
        private String playInstanceId;
        public Date getLastCheckinTime() { return lastCheckinTime; }
        public void setLastCheckinTime(Date value) { lastCheckinTime = value; }
        public Long getActivityId() { return activityId; }
        public void setActivityId(Long value) { activityId = value; }
        public Long getTopicId() { return topicId; }
        public void setTopicId(Long value) { topicId = value; }
        public String getPlayInstanceId() { return playInstanceId; }
        public void setPlayInstanceId(String value) { playInstanceId = value; }
    }

    /** 按主键取单行。 */
    PlayerNodeProgress selectPlayerNodeProgressById(@Param("id") Long id);

    /** 至少完成过一个节点的主题玩家数。 */
    long countPlayersByTopicId(@Param("topicId") Long topicId);

    int insertPlayerNodeProgress(PlayerNodeProgress playerNodeProgress);

    int updateShopDayPhoto(@Param("id") Long id, @Param("picUrl") String picUrl);

    /** Only the verified owner/node/current instance may append the first arrival photo. */
    int updateShopDayPhotoForInstance(@Param("id") Long id, @Param("memberId") Long memberId,
            @Param("activityId") Long activityId, @Param("topicId") Long topicId,
            @Param("nodeId") Long nodeId, @Param("playInstanceId") String playInstanceId,
            @Param("picUrl") String picUrl);


    /**
     * 拍照一律机审(2026-09-17 拍板,人工审核已下线):存量待审行(status=0)0→1 条件更新。
     * 返回 1 = 本次是唯一一次「完成事实成立」;返回 0 = 已完成/并发已处理 ⇒ 调用方不得再发奖。
     * checkin_time 改写为完成时刻(完成时间/名次同分 tie-break 用)。
     */
    int markPhotoMachineApproved(@Param("id") Long id);

    /** 已校验当前局后按持久化归属和实例条件转正，保留首次完成的唯一性。 */
    int markPhotoMachineApprovedForInstance(@Param("id") Long id,
            @Param("memberId") Long memberId, @Param("activityId") Long activityId,
            @Param("topicId") Long topicId, @Param("playInstanceId") String playInstanceId);

    /** 探店日游戏完成:进店行 status 0→1 条件更新,返回行数即「是否首次完成」(并发安全)。 */
    int markShopDayGameDone(@Param("memberId") Long memberId, @Param("activityId") Long activityId,
            @Param("topicId") Long topicId, @Param("nodeId") Long nodeId);

    /** 调用方必须先校验当前局；旧入口只更新 default 局。 */
    int markShopDayGameDoneForInstance(@Param("memberId") Long memberId,
            @Param("activityId") Long activityId, @Param("topicId") Long topicId,
            @Param("nodeId") Long nodeId, @Param("playInstanceId") String playInstanceId);

    /** 已完成全部谜题节点的各场次总分中最高值；未形成完整谜题局时返回 null。 */
    Integer selectPuzzlePersonalBest(@Param("memberId") Long memberId, @Param("topicId") Long topicId);

    /** 活动/主题排行榜:完成数(status=1)降序,同数先完成者靠前;score=完成节点数。 */
    List<com.chengyinhub.business.domain.vo.LeaderboardItemVO> selectActivityLeaderboard(
            @Param("activityId") Long activityId, @Param("topicId") Long topicId, @Param("limit") int limit);
}
