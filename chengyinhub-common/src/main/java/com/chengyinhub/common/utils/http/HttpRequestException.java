package com.chengyinhub.common.utils.http;

/**
 * 明确表示第三方 HTTP 调用失败，避免把网络失败伪装成成功的空响应。
 * 不保留 URLConnection 的原始异常消息，因为其中可能携带含凭据的完整 URL。
 */
public class HttpRequestException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    private final int statusCode;
    private final boolean retryable;
    private final String requestId;

    public HttpRequestException(String message, int statusCode, boolean retryable)
    {
        this(message, statusCode, retryable, null);
    }

    public HttpRequestException(String message, int statusCode, boolean retryable, String requestId)
    {
        super(message);
        this.statusCode = statusCode;
        this.retryable = retryable;
        this.requestId = requestId;
    }

    public int getStatusCode()
    {
        return statusCode;
    }

    public boolean isRetryable()
    {
        return retryable;
    }

    String getRequestId()
    {
        return requestId;
    }
}
