package com.chengyinhub.business.citygame.analytics.fairness.contract;

/**
 * Frozen field and version contract for the W20.21 member cohort.
 * B4-2 implements against this file. Thresholds come only from an approved parameter row.
 * An unset or unrecognised config key fails closed. This file does not supply a numeric default.
 */
public final class FairnessFieldContract {
    public static final String FORMULA_ID = "city-fairness-cohort-v1";
    public static final String STATE_RULE = "member-cohort-v1";
    public static final String NO_MATURE_SAMPLE = "NO_MATURE_SAMPLE";
    public static final String COUNTED = "COUNTED";

    public static final String METRIC_VERSION = "metricVersion";
    public static final String DELTA_MILLIS = "deltaMillis";
    public static final String HORIZON_MILLIS = "horizonMillis";
    public static final String GRACE_MILLIS = "graceMillis";
    public static final String MERGE_WINDOW_MILLIS = "mergeWindowMillis";
    public static final String STATE_RULE_KEY = "stateRule";
    public static final String MAX_EVENTS = "maxEvents";
    public static final String PAGE_SIZE = "pageSize";

    public static final String SOURCE = "source";
    public static final String EVENT_ID = "eventId";
    public static final String OUTING_ID = "outingId";
    public static final String KIND = "kind";
    public static final String ACTOR_ID = "actorId";
    public static final String OCCURRED_AT = "occurredAt";
    public static final String KNOWN_AT = "knownAt";
    public static final String SUBJECT_SOURCE = "subjectSource";
    public static final String SUBJECT_EVENT_ID = "subjectEventId";
    public static final String JOIN_TIME = "joinTime";
    public static final String TEAM_SIZE = "teamSize";
    public static final String ACTION_COST = "actionCost";
    public static final String NEWCOMER = "newcomer";
    public static final String NEWCOMER_QUEUE = "NEWCOMER";
    public static final String RETURNING_QUEUE = "RETURNING";
    public static final String GAIN_AMOUNT = "gainAmount";
    public static final String VERIFICATION_MILLIS = "verificationMillis";
    public static final String BOARD_ID = "boardId";
    public static final String REGION_ID = "regionId";
    public static final String SEASON_ID = "seasonId";
    public static final String RULES_VERSION = "rulesVersion";

    public static final String EFFECTIVE_TASK = "EFFECTIVE_TASK";
    public static final String EFFECTIVE_SERVICE = "EFFECTIVE_SERVICE";
    public static final String GOAL_COMPLETED = "GOAL_COMPLETED";
    public static final String GAIN = "GAIN";
    public static final String ZERO_POINT_BLOCK = "ZERO_POINT_BLOCK";
    public static final String CHALLENGE_COMPENSATION_EXIT = "CHALLENGE_COMPENSATION_EXIT";
    public static final String GPS_FALSE_REJECTION = "GPS_FALSE_REJECTION";
    public static final String SAFETY_TICKET = "SAFETY_TICKET";
    public static final String MERCHANT_VERIFICATION = "MERCHANT_VERIFICATION";
    public static final String SCAN = "SCAN";
    public static final String EXIT = "EXIT";
    public static final String LOW_ACTIVITY = "LOW_ACTIVITY";
    public static final String CONFIRM_CHEAT = "CONFIRM_CHEAT";
    public static final String TEST_MEMBER = "TEST_MEMBER";
    public static final String REVOKE = "REVOKE";
    public static final String APP_REOPEN = "APP_REOPEN";
    public static final String RECONNECT = "RECONNECT";
    public static final String REGISTRATION_REPEAT = "REGISTRATION_REPEAT";

    public static final String MATURE = "MATURE";
    public static final String IMMATURE = "IMMATURE";
    public static final String EXCLUDED = "EXCLUDED";
    public static final String AWAITING_MATURITY = "awaiting-maturity";
    public static final String SECOND_OUTING = "second-outing";
    public static final String NO_SECOND_OUTING = "no-second-outing";
    public static final String TEST = "test";
    public static final String CHEAT = "confirm-cheat";
    public static final String NO_T0 = "no-t0";

    private FairnessFieldContract() {}
}
