package com.chengyinhub.business.externalgame;

/**
 * Bridge from external game bindings and result callbacks into journey topic nodes, XP, and medals.
 * Implemented in chengyinhub-admin so chengyinhub-system remains decoupled from play application services.
 */
public interface ExternalGameNodeBridge {
    record NodeOwnerView(long nodeId, long topicId) {}

    record NodePlayOutcome(int xpAwarded, Integer medalRank, boolean nodeCompleted) {}

    NodeOwnerView requireOwnedNode(long creatorMemberId, long nodeId);

    NodePlayOutcome onGameResult(long memberId, String gameId, String sessionId,
                                 Long nodeId, Long topicId, Long activityId,
                                 Integer score, boolean passed);
}
