package com.chengyinhub.business.service.ai;

/** Server-validated original command; contains no resolved signed media URL or image bytes. */
public record PhotoCheckIntentCommand(
        Long memberId, Long sessionId, Long activityId, Long topicId, Long nodeId, Long ownerId,
        Long originalGameSessionId, Long originalRouteSessionId, Integer ownerType,
        Integer expectedVersion, Integer authorityEpoch, String playInstanceId, String actionKey,
        String requestFingerprint, String imageReference, String requirement, String configSnapshotHash) { }
