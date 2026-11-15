package com.chengyinhub.business.moderation;

/** A machine moderation provider's answer for one piece of content (SEC-12). */
public enum ModerationVerdict {
    /** Judged and allowed. */
    PASS,
    /** Judged and refused. */
    RISK,
    /** The provider asks a person to look. */
    REVIEW,
    /** The provider could not judge (not configured, network, quota, bad response). Never a pass. */
    UNAVAILABLE
}
