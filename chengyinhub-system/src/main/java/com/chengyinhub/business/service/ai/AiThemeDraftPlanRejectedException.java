package com.chengyinhub.business.service.ai;

/** A zero-effect preflight rejection, not a provider/transport disposition. */
public final class AiThemeDraftPlanRejectedException extends AiServiceException
{
    public enum Code { AUTHORITY_CHANGED, PLAN_EXPIRED, CONFIG_CHANGED, PLAN_INVALID, CHECK_UNAVAILABLE }

    private final Code code;

    public AiThemeDraftPlanRejectedException(Code code)
    {
        super(Reason.UPSTREAM_UNAVAILABLE, "AI 草稿原计划不可执行");
        this.code = java.util.Objects.requireNonNull(code);
    }

    public Code getCode() { return code; }
}
