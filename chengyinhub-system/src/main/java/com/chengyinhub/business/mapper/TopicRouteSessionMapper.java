package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.TopicRouteAction;
import com.chengyinhub.business.domain.TopicRouteSession;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface TopicRouteSessionMapper {
<<<<<<< public
    String selectMemberNickname(@Param("memberId") Long memberId);
=======
    List<TopicRouteSession> selectCurrentCandidatesByOwners(@Param("scopes") List<TopicRouteSession> scopes);
    TopicRouteSession selectCurrentByOwnerForUpdate(@Param("activityId") Long activityId, @Param("topicId") Long topicId, @Param("memberId") Long memberId);
    TopicRouteSession selectByOwnerForUpdate(@Param("activityId") Long activityId, @Param("topicId") Long topicId, @Param("memberId") Long memberId, @Param("playInstanceId") String playInstanceId);
    TopicRouteSession selectAccessibleByIdForUpdate(@Param("id") Long id, @Param("memberId") Long memberId);
>>>>>>> private-github/featu
    int insertSession(TopicRouteSession session);
    TopicRouteSession selectAccessibleByIdForUpdate(@Param("id") Long id,@Param("memberId") Long memberId);
    List<TopicRouteSession> selectHistoryPage(@Param("memberId") Long memberId,@Param("topicId") Long topicId,@Param("beforeSessionId") Long beforeSessionId,@Param("limit") int limit);
    List<String> selectEndingResults(@Param("memberId") Long memberId, @Param("topicId") Long topicId);
    int countRuns(@Param("activityId") Long activityId,@Param("topicId") Long topicId,@Param("memberId") Long memberId);
    TopicRouteSession selectCurrentByOwner(@Param("activityId") Long activityId, @Param("topicId") Long topicId, @Param("memberId") Long memberId);
    List<TopicRouteSession> selectHistory(@Param("memberId") Long memberId, @Param("topicId") Long topicId);
    TopicRouteSession selectByOwner(@Param("activityId") Long activityId, @Param("topicId") Long topicId,
                                    @Param("memberId") Long memberId,
                                    @Param("playInstanceId") String playInstanceId);
    /** 旧客户端未带场次时仅用于证明唯一局，最多取两条；禁止按最新局猜测。 */
    List<TopicRouteSession> selectMemberTopicSessions(@Param("memberId") Long memberId,
                                                     @Param("topicId") Long topicId);
    TopicRouteSession selectAccessibleById(@Param("id") Long id, @Param("memberId") Long memberId);
    List<TopicRouteSession> selectByOwners(@Param("scopes") List<TopicRouteSession> scopes);
    int updateStateCas(TopicRouteSession session);
    TopicRouteAction selectActionByAction(@Param("sessionId") Long sessionId, @Param("memberId") Long memberId,
                                          @Param("actionId") String actionId);
    TopicRouteAction selectActionByReceipt(@Param("sessionId") Long sessionId, @Param("receiptId") String receiptId);
    int insertAction(TopicRouteAction action);
    int updateActionResponse(@Param("id") Long id, @Param("responseJson") String responseJson);
    List<Long> selectDistinctMemberIdsByTopic(@Param("topicId") Long topicId, @Param("status") Integer status);
    int countDistinctMembersByTopic(@Param("topicId") Long topicId, @Param("status") Integer status);
}
