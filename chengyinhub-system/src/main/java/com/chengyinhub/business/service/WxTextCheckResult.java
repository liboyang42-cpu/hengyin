package com.chengyinhub.business.service;

/**
 * 微信同步文本审核的结构化结论。
 *
 * <p>{@link Status} 决定业务动作，{@link Reason} 保留可观测的真实原因；调用方不得再从展示文案
 * 推断是内容违规还是审核服务不可用。</p>
 */
public final class WxTextCheckResult
{
    public enum Status
    {
        PASS,
        CONTENT_REJECT,
        SERVICE_UNAVAILABLE
    }

    public enum Reason
    {
        PASS,
        DISABLED_OR_EMPTY,
        RISKY,
        REVIEW_REQUIRED,
        MISSING_CONTEXT,
        TOKEN_UNAVAILABLE,
        TIMEOUT_OR_NETWORK,
        UNKNOWN_RESPONSE,
        SERVICE_ERROR
    }

    private final Status status;
    private final Reason reason;
    private final String message;

    private WxTextCheckResult(Status status, Reason reason, String message)
    {
        this.status = status;
        this.reason = reason;
        this.message = message;
    }

    public static WxTextCheckResult pass(Reason reason)
    {
        return new WxTextCheckResult(Status.PASS, reason, null);
    }

    public static WxTextCheckResult reject(Reason reason, String message)
    {
        return new WxTextCheckResult(Status.CONTENT_REJECT, reason, message);
    }

    public static WxTextCheckResult unavailable(Reason reason, String message)
    {
        return new WxTextCheckResult(Status.SERVICE_UNAVAILABLE, reason, message);
    }

    public Status getStatus()
    {
        return status;
    }

    public Reason getReason()
    {
        return reason;
    }

    public String getMessage()
    {
        return message;
    }
}
