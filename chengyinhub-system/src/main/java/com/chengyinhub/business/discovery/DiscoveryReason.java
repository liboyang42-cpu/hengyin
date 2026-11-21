package com.chengyinhub.business.discovery;

/** K2 discovery: the one plain reason shown on each "near you" item. */
public enum DiscoveryReason {
    /** Close to where the player is looking. */
    NEAR_YOU,
    /** An event of a club the player belongs to. */
    YOUR_CLUB,
    /** Added in the last {@link DiscoveryScorer#NEW_WINDOW_DAYS} days. */
    NEW
}
