package com.chengyinhub.business.citygame.analytics.fairness;

import com.chengyinhub.business.citygame.analytics.fairness.contract.FairnessFieldContract;

import java.util.List;

/**
 * Names shared inside this directory. The cohort formula and its config keys live in
 * {@link FairnessFieldContract}. The value group must not add a second copy.
 */
public final class FairnessFactContract {
    public static final String NAME = FairnessFieldContract.FORMULA_ID;

    public static final String EFFECTIVE_TASK = FairnessFieldContract.EFFECTIVE_TASK;
    public static final String EFFECTIVE_SERVICE = FairnessFieldContract.EFFECTIVE_SERVICE;
    public static final String GOAL_COMPLETED = FairnessFieldContract.GOAL_COMPLETED;
    public static final String GAIN = FairnessFieldContract.GAIN;
    public static final String ZERO_POINT_BLOCK = FairnessFieldContract.ZERO_POINT_BLOCK;
    public static final String CHALLENGE_COMPENSATION_EXIT = FairnessFieldContract.CHALLENGE_COMPENSATION_EXIT;
    public static final String GPS_FALSE_REJECTION = FairnessFieldContract.GPS_FALSE_REJECTION;
    public static final String SAFETY_TICKET = FairnessFieldContract.SAFETY_TICKET;
    public static final String MERCHANT_VERIFICATION = FairnessFieldContract.MERCHANT_VERIFICATION;
    public static final String SCAN = FairnessFieldContract.SCAN;
    public static final String EXIT = FairnessFieldContract.EXIT;
    public static final String LOW_ACTIVITY = FairnessFieldContract.LOW_ACTIVITY;
    public static final String CONFIRM_CHEAT = FairnessFieldContract.CONFIRM_CHEAT;
    public static final String TEST_MEMBER = FairnessFieldContract.TEST_MEMBER;
    public static final String REVOKE = FairnessFieldContract.REVOKE;
    public static final String APP_REOPEN = FairnessFieldContract.APP_REOPEN;
    public static final String RECONNECT = FairnessFieldContract.RECONNECT;
    public static final String REGISTRATION_REPEAT = FairnessFieldContract.REGISTRATION_REPEAT;

    public static final String NEWCOMER = FairnessFieldContract.NEWCOMER_QUEUE;
    public static final String RETURNING = FairnessFieldContract.RETURNING_QUEUE;

    public static final String DELTA_MILLIS = FairnessFieldContract.DELTA_MILLIS;
    public static final String HORIZON_MILLIS = FairnessFieldContract.HORIZON_MILLIS;
    public static final String GRACE_MILLIS = FairnessFieldContract.GRACE_MILLIS;
    public static final String MERGE_WINDOW_MILLIS = FairnessFieldContract.MERGE_WINDOW_MILLIS;
    public static final String STATE_RULE = FairnessFieldContract.STATE_RULE_KEY;
    public static final String METRIC_VERSION = FairnessFieldContract.METRIC_VERSION;
    public static final String PARAMETER_APPROVAL = "parameter-approval";
    public static final String PARAMETER_SOURCE_REF = "parameter-source-ref";
    public static final String MAX_EVENTS = FairnessFieldContract.MAX_EVENTS;
    public static final String PAGE_SIZE = FairnessFieldContract.PAGE_SIZE;

    public static final List<String> STREAMS = List.of(
            "goals", "effective-gains", "concentration", "zero-point-blocks",
            "challenge-compensation-exits", "gps-false-rejections", "safety-tickets",
            "merchant-verification", "outings");

    private FairnessFactContract() {}
}
