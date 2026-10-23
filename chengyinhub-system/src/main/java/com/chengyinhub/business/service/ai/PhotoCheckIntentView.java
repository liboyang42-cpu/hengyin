package com.chengyinhub.business.service.ai;

/** Provider uncertainty stays separate from a durable local fallback decision. */
public record PhotoCheckIntentView(
        Long intentId, Long memberId, Long sessionId, Long attemptLogId, String actionKey,
        String requestFingerprint, PhotoCheckIntentState state, PhotoCheckVisionResult result,
        String reasonCode, String providerState) { }
