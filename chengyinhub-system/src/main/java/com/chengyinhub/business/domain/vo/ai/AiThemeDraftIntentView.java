package com.chengyinhub.business.domain.vo.ai;

import java.time.Instant;

public record AiThemeDraftIntentView(String requestId, AiThemeDraftIntentState state,
        boolean replayed, boolean contextAvailable, boolean resultAvailable,
        AiThemeDraftResp result, String reasonCode, Instant executeBefore, Instant resultExpiresAt, Integer productType) { }
