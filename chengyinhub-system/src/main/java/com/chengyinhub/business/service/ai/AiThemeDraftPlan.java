package com.chengyinhub.business.service.ai;

import java.time.Instant;

/** Server-only original plan. Never bind this record from a controller request. */
public record AiThemeDraftPlan(Long memberId, String role, String pool, String originalDay,
        String counterKey, String nonce, String receiptKey, String reservation,
        String creatorConfigHash, String gatewayConfigHash, Instant executeBefore) { }
