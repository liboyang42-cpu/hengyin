package com.chengyinhub.business.service.minor;

/** 监护授权的可映射业务拒绝。 */
public class MinorGuardianException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public static final String ARGUMENT_INVALID = "GUARDIAN_ARGUMENT_INVALID";
    public static final String PURPOSE_INVALID = "GUARDIAN_PURPOSE_INVALID";
    public static final String RELATION_INVALID = "GUARDIAN_RELATION_INVALID";
    public static final String POLICY_VERSION_MISMATCH = "GUARDIAN_POLICY_VERSION_MISMATCH";
    public static final String GUARDIAN_NOT_ADULT = "GUARDIAN_NOT_ADULT";
    public static final String WARD_NOT_MINOR = "GUARDIAN_WARD_NOT_MINOR";
    public static final String WARD_AGE_UNKNOWN = "GUARDIAN_WARD_AGE_UNKNOWN";
    public static final String SELF_GUARDIAN = "GUARDIAN_SELF";
    public static final String INVITE_INVALID = "GUARDIAN_INVITE_INVALID";
    public static final String NOT_FOUND = "GUARDIAN_NOT_FOUND";
    public static final String STATE_CONFLICT = "GUARDIAN_STATE_CONFLICT";

    private final String errorCode;

    public MinorGuardianException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() { return errorCode; }
}
