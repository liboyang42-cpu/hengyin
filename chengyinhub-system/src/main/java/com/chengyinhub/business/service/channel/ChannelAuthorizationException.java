package com.chengyinhub.business.service.channel;

/**
 * C08 独占授权失败。不是公共 IM send/receipt 协议错误。
 */
public final class ChannelAuthorizationException extends IllegalStateException {

    public enum Code {
        RULES_CONSENT_REQUIRED,
        RULES_CAS_CONFLICT,
        NOT_MEMBER,
        REVOKED,
        ISOLATED_LATE_REQUEST,
        FRIEND_PERMISSION_REQUIRED,
        CHANNEL_PERMISSION_REQUIRED,
        PROGRESS_PRIVACY_DENIED,
        ACCOUNT4_MISMATCH,
        HISTORICAL_VOTE_WRITE_DENIED,
        INVALID_CATALOG,
        ROLE_REQUIRED
    }

    public final Code code;

    public ChannelAuthorizationException(Code code) {
        super(code.name());
        this.code = code;
    }
}
