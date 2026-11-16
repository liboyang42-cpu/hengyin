package com.chengyinhub.business.externalgame;

/**
 * Bridge from external game bindings and result callbacks into journey topic nodes, XP, and medals.
 * Implemented in chengyinhub-admin so chengyinhub-system remains decoupled from play application services.
 */
public interface ExternalGameNodeBridge {
    record NodeOwnerView(long nodeId, long topicId) {}

    record NodePlayOutcome(int xpAwarded, Integer medalRank, boolean nodeCompleted) {}

    /**
     * One finished play session, as judged by the server.
     *
     * @param attested            the result carried a signature from a server-side game authority (per-game secret or
     *                            platform key) that the client never holds; only attested results may complete a node
     * @param standaloneXpAllowed the server-side per-game/day and daily XP caps still allow the standalone pass XP
     */
    record GameResult(long memberId, String gameId, String sessionId, Long nodeId, Long topicId, Long activityId,
                      Integer score, boolean passed, boolean attested, boolean standaloneXpAllowed) {}

    NodeOwnerView requireOwnedNode(long creatorMemberId, long nodeId);

    NodePlayOutcome onGameResult(GameResult result);
}
