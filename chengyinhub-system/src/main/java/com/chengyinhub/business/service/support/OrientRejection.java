package com.chengyinhub.business.service.support;

/**
 * 城市定向(Doc2 F1d)带机器可读 errorCode 的业务拒绝;Controller 把 errorCode 放进 AjaxResult,
 * 小程序按 errorCode 分支,不解析文案(同 IPlayTeamService.TeamRejection 的约定)。
 */
public class OrientRejection extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public static final String DISABLED = "ORIENT_DISABLED";
    public static final String LOGIN_REQUIRED = "ORIENT_LOGIN_REQUIRED";
    public static final String BAD_REQUEST = "ORIENT_BAD_REQUEST";
    public static final String NOT_FOUND = "ORIENT_NOT_FOUND";
    public static final String NOT_OWNER = "ORIENT_NOT_OWNER";
    public static final String NOT_ORIENTEERING = "ORIENT_NOT_ORIENTEERING";
    public static final String NOT_PARTICIPANT = "ORIENT_NOT_PARTICIPANT";
    public static final String COORD_SYS_INVALID = "ORIENT_COORD_SYS_INVALID";
    public static final String COORD_INVALID = "ORIENT_COORD_INVALID";
    public static final String ALT_NOT_CONFIGURED = "ORIENT_ALT_NOT_CONFIGURED";
    public static final String ALT_TYPE_INVALID = "ORIENT_ALT_TYPE_INVALID";
    public static final String ALT_VERIFY_FAILED = "ORIENT_ALT_VERIFY_FAILED";
    public static final String TASK_DELISTED = "ORIENT_TASK_DELISTED";
    public static final String PHOTO_PENDING = "ORIENT_PHOTO_PENDING";
    public static final String PHOTO_INVALID = "ORIENT_PHOTO_INVALID";
    public static final String IDEMPOTENCY_CONFLICT = "ORIENT_IDEMPOTENCY_CONFLICT";
    public static final String ATTEMPT_NOT_PENDING = "ORIENT_ATTEMPT_NOT_PENDING";
    public static final String TEAM_NOT_MEMBER = "ORIENT_TEAM_NOT_MEMBER";
    public static final String SAFETY_BLOCKED = "ORIENT_SAFETY_BLOCKED";
    public static final String INSURANCE_NOT_MANDATORY = "ORIENT_INSURANCE_NOT_MANDATORY";

    private final String errorCode;

    public OrientRejection(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() { return errorCode; }
}
