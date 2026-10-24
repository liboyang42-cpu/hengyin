package com.chengyinhub.business.service.minor;

/**
 * 执行挂点的判定结果。allowed=false 时 errorCode 为稳定机器码，message 为可直接展示的中文。
 */
public record MinorDecision(boolean allowed, String errorCode, String message, AgeBand ageBand) {
    public static final String AGE_UNKNOWN = "MINOR_AGE_UNKNOWN";
    public static final String UNDER_8_GUARDIAN_PURCHASE = "MINOR_UNDER_8_GUARDIAN_PURCHASE";
    public static final String PURCHASE_CONSENT_REQUIRED = "MINOR_GUARDIAN_PURCHASE_CONSENT_REQUIRED";
    public static final String INFO_PROCESS_CONSENT_REQUIRED = "MINOR_GUARDIAN_INFO_CONSENT_REQUIRED";
    public static final String ROUTE_NOT_LOW_RISK = "MINOR_ROUTE_NOT_LOW_RISK";
    public static final String CURFEW = "MINOR_CURFEW";
    public static final String GUARDIAN_IN_TEAM_REQUIRED = "MINOR_GUARDIAN_IN_TEAM_REQUIRED";
    public static final String SOLO_AUTHORIZATION_REQUIRED = "MINOR_SOLO_AUTHORIZATION_REQUIRED";

    public static MinorDecision allow(AgeBand band) {
        return new MinorDecision(true, null, null, band);
    }

    public static MinorDecision deny(AgeBand band, String errorCode, String message) {
        return new MinorDecision(false, errorCode, message, band);
    }
}
