package com.chengyinhub.business.locationtrust;

/** Outcome of scoring one location write. Severity grows downwards. */
public enum LocationTrustDecision {
    /** Write proceeds as before. */
    ACCEPT,
    /** Write is kept on record for review, but nothing is credited (no XP, tiles, visit or progress). */
    ACCEPT_NO_REWARD,
    /** Write is refused with a retryable message (only for clearly impossible input). */
    REJECT;

    public LocationTrustDecision max(LocationTrustDecision other) {
        return other != null && other.ordinal() > ordinal() ? other : this;
    }
}
