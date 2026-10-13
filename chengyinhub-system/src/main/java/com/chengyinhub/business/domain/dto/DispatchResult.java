package com.chengyinhub.business.domain.dto;

/** 调配中枢的稳定结果，供后台和小程序入口共享同一套失败语义。 */
public class DispatchResult {

    public enum Code {
        SELECTED,
        REJECTED,
        REGISTRATION_NOT_FOUND,
        TOPIC_NOT_FOUND,
        CLUB_DISSOLVED,
        MERCHANT_INACTIVE,
        SLOT_OCCUPIED,
        ALREADY_PROCESSED,
        NO_PENDING_CANDIDATE
    }

    private final Code code;
    private final Long registrationId;
    private final String message;

    private DispatchResult(Code code, Long registrationId, String message) {
        this.code = code;
        this.registrationId = registrationId;
        this.message = message;
    }

    public static DispatchResult selected(Long registrationId) {
        return new DispatchResult(Code.SELECTED, registrationId, "已选定候选");
    }

    public static DispatchResult registrationRejected(Long registrationId) {
        return new DispatchResult(Code.REJECTED, registrationId, "已驳回候选");
    }

    public static DispatchResult rejected(Code code, String message) {
        return new DispatchResult(code, null, message);
    }

    public boolean isSelected() { return code == Code.SELECTED; }
    public boolean isRejected() { return code == Code.REJECTED; }
    public Code getCode() { return code; }
    public Long getRegistrationId() { return registrationId; }
    public String getMessage() { return message; }
}
