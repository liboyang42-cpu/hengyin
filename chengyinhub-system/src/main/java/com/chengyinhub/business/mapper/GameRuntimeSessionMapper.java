package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.GameRuntimeSession;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

public interface GameRuntimeSessionMapper {
    /** Same member/team visibility as the existing selectors, only the no-route default instance. */
    List<GameRuntimeSession> selectDefaultMemberSessionsByTopic(@Param("activityId") Long activityId,
            @Param("topicId") Long topicId, @Param("memberId") Long memberId);

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

    GameRuntimeSession selectAccessibleById(@Param("id") Long id, @Param("memberId") Long memberId);

    GameRuntimeSession selectPlayerByOwner(@Param("activityId") Long activityId,
                                           @Param("topicId") Long topicId,
                                           @Param("nodeId") Long nodeId,
                                           @Param("memberId") Long memberId);

    /** 某玩家在某主题(某场次)下的全部个人玩法会话,按先后。结局页「这一生的档案」用。 */
    List<GameRuntimeSession> selectPlayerSessionsByTopic(@Param("activityId") Long activityId,
                                                         @Param("topicId") Long topicId,
                                                         @Param("memberId") Long memberId);

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
