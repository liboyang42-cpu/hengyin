package com.chengyinhub.business.locationtrust;

import java.util.Locale;

/** What a configurable rule does when it fires. FLAG only writes the review trail. */
public enum LocationTrustAction {
    FLAG(LocationTrustDecision.ACCEPT),
    NO_REWARD(LocationTrustDecision.ACCEPT_NO_REWARD),
    REJECT(LocationTrustDecision.REJECT);

    private final LocationTrustDecision decision;

    LocationTrustAction(LocationTrustDecision decision) {
        this.decision = decision;
    }

    public LocationTrustDecision decision() {
        return decision;
    }

    /** Unknown or blank text falls back to the given default, never to something stricter. */
    public static LocationTrustAction parse(String raw, LocationTrustAction fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
