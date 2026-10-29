package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.GameActorContext;
import com.chengyinhub.business.domain.dto.GameMerchantEntryView;
import com.chengyinhub.business.domain.dto.GameCommandReceiptView;
import com.chengyinhub.business.domain.dto.GameSessionCommand;
import com.chengyinhub.business.domain.dto.GameSessionView;

import java.util.List;
import java.util.Map;

/** 活动级三身份游戏深模块公开 seam。 */
public interface IGameSessionRuntimeService {
    /** Persisted projection: null only when no shared session applies; a missing shared RUN rejects without initialization/refresh. */
    com.chengyinhub.business.domain.route.TopicRouteState querySharedRouteState(Long memberId, Long topicId, Long activityId);
    /** Server-only original snapshot. No initialization, zero-HP refresh, effects or writes. */
    default com.fasterxml.jackson.databind.JsonNode querySharedRouteSnapshot(Long memberId, Long topicId, Long activityId, Long nodeId) { return null; }
    default com.chengyinhub.business.domain.route.TopicRouteState sharedRouteState(Long memberId, Long topicId, Long activityId) { return null; }
    default com.fasterxml.jackson.databind.JsonNode sharedRouteSnapshot(Long memberId, Long topicId, Long activityId, Long nodeId) { return null; }
    default com.fasterxml.jackson.databind.JsonNode recordSharedZeroChallengeResult(Long memberId,Long topicId,Long activityId,Long nodeId,String instance,String submissionId,boolean passed,Long hits,boolean retry,Integer authorityEpoch) { throw new UnsupportedOperationException(); }
    Map<String, Object> recordSharedBasicArrival(Long memberId, Long topicId, Long activityId, Long nodeId, String actionId, String kind, String instance, Integer authorityEpoch);
    void assertSharedRouteWritable(Long memberId, Long topicId, Long activityId, Long nodeId, Integer authorityEpoch);
    default void assertSharedGameplayOutcomeStart(Long memberId,Long topicId,Long activityId,Long nodeId,String instance,Integer authorityEpoch){throw new UnsupportedOperationException();}
    default com.chengyinhub.business.domain.route.PlayCheckReceipt rollSharedGameplayCheck(Long memberId,Long topicId,Long activityId,Long nodeId,String instance,String attemptId,boolean reroll,Integer authorityEpoch){throw new UnsupportedOperationException();}
    default com.fasterxml.jackson.databind.node.ObjectNode sharedGameplayCheckActions(Long memberId,Long topicId,Long activityId,Long nodeId,String instance,String attemptId,Integer authorityEpoch){throw new UnsupportedOperationException();}
    default com.fasterxml.jackson.databind.JsonNode sharedGameplayFeedback(Long memberId,Long activityId,Long gameSessionId,Long teamId,Integer epoch,String attemptId,String optionId){throw new UnsupportedOperationException();}
    default void recordSharedGameplayOutcome(Long memberId,Long topicId,Long activityId,Long nodeId,String instance,String attemptId,com.fasterxml.jackson.databind.JsonNode decision,java.util.List<String[]> choices,Integer authorityEpoch) {throw new UnsupportedOperationException();}
    default boolean recordOriginalSharedGameplayOutcome(Long memberId,Long activityId,Long gameSessionId,Long teamId,Integer epoch,Long nodeId,String instance,String attemptId,com.fasterxml.jackson.databind.JsonNode decision,java.util.List<String[]> choices) {return false;}
    void recordSharedRouteResult(Long memberId, Long topicId, Long activityId, Long nodeId, String instance, String actionId, Boolean passed, java.util.List<String[]> choices, Integer authorityEpoch);
    Map<String, Object> sharedControlView(GameActorContext actor, Long activityId);
    default void recordSharedRouteResult(Long memberId, Long topicId, Long activityId, Long nodeId, String instance, String actionId, Boolean passed, java.util.List<String[]> choices) { throw new UnsupportedOperationException(); }

    Map<String, Object> exportSharedRun(GameActorContext actor, Long activityId);
    Map<String, Object> anchorSharedRun(GameActorContext actor, Long activityId);
    Map<String, Object> sharedPreview(GameActorContext actor, Long activityId, String action, Map<String, Object> payload);

    boolean isGameModuleConfigEnabled(String configJson);
    boolean isGameSessionEnabled(com.chengyinhub.business.domain.CmsTopic topic);
    String routeSessionStatus(Long activityId);
    boolean isRouteLeaderboardHidden(Long activityId);
    void assertRoutePlayable(Long activityId, Long nodeId);
    void assertRouteReception(Long activityId, Long nodeId);
    List<GameMerchantEntryView> merchantEntries(GameActorContext actor);
    GameSessionView view(GameActorContext actor, Long activityId);
    Map<String, Object> exportRecap(GameActorContext actor, Long activityId);
    GameCommandReceiptView command(GameActorContext actor, GameSessionCommand command);
    GameCommandReceiptView recordFailure(GameActorContext actor, GameSessionCommand command, GameSessionException failure);
    GameCommandReceiptView receipt(GameActorContext actor, Long activityId, String requestId);


    boolean isOriginalSharedRouteEligible(Long memberId, Long topicId, Long activityId, Long nodeId,
                                          Long sessionId, String runtimeInstance, Integer authorityEpoch);
    boolean isOriginalSharedRouteEligibleForUpdate(Long memberId, Long topicId, Long activityId, Long nodeId,
                                                   Long sessionId, String runtimeInstance, Integer authorityEpoch);
}
