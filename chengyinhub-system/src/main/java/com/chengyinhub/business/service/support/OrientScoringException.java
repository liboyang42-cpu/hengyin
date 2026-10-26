package com.chengyinhub.business.service.support;

/** F1b 城市定向计分业务错误;errorCode 原样下发给端上(AjaxResult.errorCode)。 */
public class OrientScoringException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public static final String DISABLED = "ORIENT_SCORING_DISABLED";
    public static final String NOT_APPLICABLE = "ORIENT_SCORING_NOT_APPLICABLE";
    public static final String SESSION_NOT_FOUND = "ORIENT_SESSION_NOT_FOUND";
    public static final String SESSION_NOT_ENDED = "ORIENT_SESSION_NOT_ENDED";
    public static final String REVIEW_PENDING = "ORIENT_REVIEW_PENDING";
    public static final String INVALID_ARGUMENT = "ORIENT_INVALID_ARGUMENT";
    public static final String IDEMPOTENCY_CONFLICT = "ORIENT_IDEMPOTENCY_CONFLICT";
    public static final String WINDOW_CLOSED = "ORIENT_WINDOW_CLOSED";
    public static final String ALREADY_OPEN = "ORIENT_ALREADY_OPEN";
    public static final String ALREADY_REVERSED = "ORIENT_ENTRY_ALREADY_REVERSED";
    public static final String ALREADY_RESOLVED = "ORIENT_ALREADY_RESOLVED";
    public static final String NOT_FOUND = "ORIENT_NOT_FOUND";

    private final String errorCode;

    public OrientScoringException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() { return errorCode; }
}
