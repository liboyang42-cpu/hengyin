package com.chengyinhub.business.service.ai;

/**
 * AI 服务异常(频控超限 / 内容安全拦截 / 模型调用失败)。
 * Controller 层 catch 后转 AjaxResult.error,不给前端暴技术细节。
 *
 * @author chengyinhub
 */
public class AiServiceException extends RuntimeException
{
    public enum Reason
    {
        RATE_LIMITED,
        INPUT_BLOCKED,
        OUTPUT_BLOCKED,
        UPSTREAM_UNAVAILABLE,
        UNKNOWN
    }

    private final Reason reason;

    public AiServiceException(String message)
    {
        this(Reason.UNKNOWN, message);
    }

    public AiServiceException(String message, Throwable cause)
    {
        this(Reason.UNKNOWN, message, cause);
    }

    public AiServiceException(Reason reason, String message)
    {
        super(message);
        this.reason = reason == null ? Reason.UNKNOWN : reason;
    }

    public AiServiceException(Reason reason, String message, Throwable cause)
    {
        super(message, cause);
        this.reason = reason == null ? Reason.UNKNOWN : reason;
    }

    public Reason getReason()
    {
        return reason;
    }
}
