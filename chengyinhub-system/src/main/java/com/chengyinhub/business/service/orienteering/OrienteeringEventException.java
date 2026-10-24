package com.chengyinhub.business.service.orienteering;

/**
 * 赛事/普通城市定向独占权威的业务拒绝。errorCode 给后续 controller hunk 原样下发，
 * 本包不写公共 Controller。
 */
public class OrienteeringEventException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public static final String NOT_ORDINARY_CITY = "EVENT_NOT_ORDINARY_CITY";
    public static final String NOT_PUBLISHED = "EVENT_NOT_PUBLISHED";
    public static final String RULE_NOT_FROZEN = "EVENT_RULE_NOT_FROZEN";
    public static final String RULE_VERSION_MISMATCH = "EVENT_RULE_VERSION_MISMATCH";
    public static final String CLUB_AUTHORITY_MISSING = "EVENT_CLUB_AUTHORITY_MISSING";
    public static final String TEAM_INELIGIBLE = "EVENT_TEAM_INELIGIBLE";
    public static final String MATCHMAKING_CANCELLED = "EVENT_MATCHMAKING_CANCELLED";
    public static final String SAFETY_UNCONFIRMED = "EVENT_SAFETY_UNCONFIRMED";
    public static final String SAFETY_BLOCKED = "EVENT_SAFETY_BLOCKED";
    public static final String TIMEZONE_NOT_CITY = "EVENT_TIMEZONE_NOT_CITY";
    public static final String GPS_FORBIDDEN = "EVENT_GPS_FORBIDDEN";
    public static final String EVIDENCE_INVALID = "EVENT_EVIDENCE_INVALID";
    public static final String GROWTH_LEADERBOARD_FORBIDDEN = "EVENT_GROWTH_LEADERBOARD_FORBIDDEN";
    public static final String IDEMPOTENCY_CONFLICT = "EVENT_IDEMPOTENCY_CONFLICT";
    public static final String NOT_FOUND = "EVENT_NOT_FOUND";
    public static final String NOT_STARTED = "EVENT_NOT_STARTED";
    public static final String NOT_RUNNING = "EVENT_NOT_RUNNING";
    public static final String PENDING_REVIEW = "EVENT_PENDING_REVIEW";
    public static final String ALREADY_TERMINAL = "EVENT_ALREADY_TERMINAL";
    public static final String INVALID_ARGUMENT = "EVENT_INVALID_ARGUMENT";
    public static final String ALREADY_RESOLVED = "EVENT_ALREADY_RESOLVED";
    public static final String WAITING_SOURCE = "EVENT_WAITING_SOURCE";

    private final String errorCode;
    private final String waitingCapability;

    public OrienteeringEventException(String errorCode, String message) {
        this(errorCode, message, null);
    }

    public OrienteeringEventException(String errorCode, String message, String waitingCapability) {
        super(message);
        this.errorCode = errorCode;
        this.waitingCapability = waitingCapability;
    }

    public String getErrorCode() {
        return errorCode;
    }

    public String getWaitingCapability() {
        return waitingCapability;
    }
}
