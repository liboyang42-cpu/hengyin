package com.chengyinhub.business.citygame.analytics.fairness;

import java.util.List;

/**
 * Minimal fact contract for this directory only.
 * The value group must not add a second copy under citygame/analytics.
 */
public final class FairnessFactContract {
    public static final String NAME = "city-fairness-fact-v1";

    public static final String EFFECTIVE_TASK = "EFFECTIVE_TASK";
    public static final String EFFECTIVE_SERVICE = "EFFECTIVE_SERVICE";
    public static final String GOAL_COMPLETED = "GOAL_COMPLETED";
    public static final String GAIN = "GAIN";
    public static final String ZERO_POINT_BLOCK = "ZERO_POINT_BLOCK";
    public static final String CHALLENGE_COMPENSATION_EXIT = "CHALLENGE_COMPENSATION_EXIT";
    public static final String GPS_FALSE_REJECTION = "GPS_FALSE_REJECTION";
    public static final String SAFETY_TICKET = "SAFETY_TICKET";
    public static final String MERCHANT_VERIFICATION = "MERCHANT_VERIFICATION";
    public static final String APP_REOPEN = "APP_REOPEN";
    public static final String RECONNECT = "RECONNECT";
    public static final String REGISTRATION_REPEAT = "REGISTRATION_REPEAT";

    public static final String NEWCOMER = "NEWCOMER";
    public static final String RETURNING = "RETURNING";

    public static final String DELTA_MILLIS = "deltaMillis";
    public static final String HORIZON_MILLIS = "horizonMillis";
    public static final String LOWER_EFFECTIVE_EVENTS = "lowerEffectiveEvents";
    public static final String PARAMETER_APPROVAL = "parameter-approval";
    public static final String PARAMETER_SOURCE_REF = "parameter-source-ref";

    public static final List<String> STREAMS = List.of(
            "goals", "effective-gains", "concentration", "zero-point-blocks",
            "challenge-compensation-exits", "gps-false-rejections", "safety-tickets",
            "merchant-verification", "outings");

    private FairnessFactContract() {}
}
