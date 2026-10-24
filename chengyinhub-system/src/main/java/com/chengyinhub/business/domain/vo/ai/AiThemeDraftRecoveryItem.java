package com.chengyinhub.business.domain.vo.ai;

import java.time.Instant;

/** Owner-only metadata recovery, including known terminal results after body retention. */
public record AiThemeDraftRecoveryItem(Long cursor, String prepareKey, String requestId,
        AiThemeDraftIntentState state, String reasonCode, Instant createdAt, Instant executeBefore,
        Instant resultExpiresAt, Integer productType) { }
