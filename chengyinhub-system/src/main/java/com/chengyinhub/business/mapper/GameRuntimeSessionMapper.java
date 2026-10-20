package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.GameRuntimeSession;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

public interface GameRuntimeSessionMapper {
    int insert(GameRuntimeSession session);
    GameRuntimeSession selectByIdForUpdate(@Param("id") Long id);
    GameRuntimeSession selectOwnerByInstance(@Param("activityId") Long activityId, @Param("topicId") Long topicId,
                                             @Param("nodeId") Long nodeId, @Param("ownerType") Integer ownerType,
                                             @Param("ownerId") Long ownerId, @Param("playInstanceId") String playInstanceId);

    GameRuntimeSession selectLatestZeroChallenge(@Param("activityId") Long activityId, @Param("topicId") Long topicId,
            @Param("nodeId") Long nodeId, @Param("ownerType") Integer ownerType, @Param("ownerId") Long ownerId,
            @Param("gameSessionId") Long gameSessionId, @Param("routeSessionId") Long routeSessionId,
            @Param("authorityEpoch") Integer authorityEpoch);

    GameRuntimeSession selectByOwner(@Param("activityId") Long activityId,
                                     @Param("topicId") Long topicId,
                                     @Param("nodeId") Long nodeId,
                                     @Param("ownerType") Integer ownerType,
                                     @Param("ownerId") Long ownerId);

    /** Explicit server-validated play instance; callers retain the legacy method for default. */
    GameRuntimeSession selectByOwnerForInstance(@Param("activityId") Long activityId,
                                                @Param("topicId") Long topicId,
                                                @Param("nodeId") Long nodeId,
                                                @Param("ownerType") Integer ownerType,
                                                @Param("ownerId") Long ownerId,
                                                @Param("playInstanceId") String playInstanceId);

    GameRuntimeSession selectAccessibleByIdForUpdate(@Param("id") Long id,@Param("memberId") Long memberId);
    GameRuntimeSession selectTeamByMemberForInstance(@Param("activityId") Long activityId,@Param("topicId") Long topicId,@Param("nodeId") Long nodeId,@Param("memberId") Long memberId,@Param("teamId") Long teamId,@Param("playInstanceId") String playInstanceId);
    List<GameRuntimeSession> selectSessionsForMemberAndTeam(@Param("activityId") Long activityId,@Param("topicId") Long topicId,@Param("memberId") Long memberId,@Param("personalInstance") String personalInstance,@Param("teamId") Long teamId,@Param("teamInstance") String teamInstance);

    GameRuntimeSession selectAccessibleById(@Param("id") Long id, @Param("memberId") Long memberId);

    GameRuntimeSession selectPlayerByOwner(@Param("activityId") Long activityId,
                                           @Param("topicId") Long topicId,
                                           @Param("nodeId") Long nodeId,
                                           @Param("memberId") Long memberId);

    GameRuntimeSession selectPlayerByOwnerForInstance(@Param("activityId") Long activityId,
                                                      @Param("topicId") Long topicId,
                                                      @Param("nodeId") Long nodeId,
                                                      @Param("memberId") Long memberId,
                                                      @Param("playInstanceId") String playInstanceId);

    /** 某玩家在某主题(某场次)下的全部个人玩法会话,按先后。结局页「这一生的档案」用。 */
    List<GameRuntimeSession> selectPlayerSessionsByTopic(@Param("activityId") Long activityId,
                                                         @Param("topicId") Long topicId,
                                                         @Param("memberId") Long memberId);

    /** Explicit archive instance; the legacy overload remains limited to default. */
    List<GameRuntimeSession> selectPlayerSessionsByTopicForInstance(@Param("activityId") Long activityId,
                                                                   @Param("topicId") Long topicId,
                                                                   @Param("memberId") Long memberId,
                                                                   @Param("playInstanceId") String playInstanceId);

    GameRuntimeSession selectTeamByMember(@Param("activityId") Long activityId,
                                          @Param("topicId") Long topicId,
                                          @Param("nodeId") Long nodeId,
                                          @Param("memberId") Long memberId);

    /**
     * 某玩家在同一场次同一主题下全部玩法会话的 state_json(读故事变量 {名字} 用,
     * 节点顺序即剧情顺序)。个人局与所在队伍的团局都算,与 selectAccessibleById 同一口径。
     */
    List<String> selectStateJsonsByTopic(@Param("memberId") Long memberId,
                                         @Param("topicId") Long topicId,
                                         @Param("activityId") Long activityId);

    /** Explicit player instance with unchanged default-instance team state. */
    List<String> selectStateJsonsByTopicForInstance(@Param("memberId") Long memberId,
                                                   @Param("topicId") Long topicId,
                                                   @Param("activityId") Long activityId,
                                                   @Param("playInstanceId") String playInstanceId);

    /** session.version 是调用方看到的旧版本；成功后数据库 version 自增。 */
    int updateStateCas(GameRuntimeSession session);
    /** 已结算反馈只更新状态JSON，不改主玩法结果或分数。 */
    int updateFeedbackStateCas(GameRuntimeSession session);

    /** 仅允许失败/超时会话原位重开，保留唯一键并用 version 防并发覆盖。 */
    int restartTerminalCas(GameRuntimeSession session);

    List<Map<String, Object>> selectLeaderboard(@Param("activityId") Long activityId,
                                                @Param("topicId") Long topicId,
                                                @Param("nodeId") Long nodeId,
                                                @Param("metric") String metric,
                                                @Param("limit") Integer limit);
}
