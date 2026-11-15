package com.chengyinhub.common.exception;

/**
 * 有类型的业务拒绝(R5 error-contract)。
 *
 * <p>与 {@link ServiceException} 并存(后者是 final,且大量既有调用依赖它的形状)。
 * 抛出后由全局异常处理器转成 {@code AjaxResult.refuse(...)}:
 * <ul>
 *   <li>不带 {@code X-Error-Contract: 2}:HTTP 200,体 {@code {code: legacyCode, msg}},与迁移前一致;</li>
 *   <li>带 {@code X-Error-Contract: 2}:HTTP {@link #getHttpStatus()},体多一个 {@code errorCode}。</li>
 * </ul>
 */
public class BusinessRefusal extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    private final ErrorCode errorCode;
    private final int httpStatus;
    private final int legacyCode;
    private final transient Object data;

    public BusinessRefusal(ErrorCode errorCode)
    {
        this(errorCode, null);
    }

    public BusinessRefusal(ErrorCode errorCode, String message)
    {
        this(errorCode, message, ErrorContract.LEGACY_ERROR_CODE, errorCode.httpStatus(), null);
    }

    public BusinessRefusal(ErrorCode errorCode, String message, int legacyCode, int httpStatus, Object data)
    {
        super(message == null || message.isEmpty() ? errorCode.defaultMessage() : message);
        this.errorCode = errorCode;
        this.legacyCode = legacyCode;
        this.httpStatus = httpStatus;
        this.data = data;
    }

    public ErrorCode getErrorCode()
    {
        return errorCode;
    }

    public String getMessageKey()
    {
        return errorCode.messageKey();
    }

    /** 新契约下建议的 HTTP 状态。 */
    public int getHttpStatus()
    {
        return httpStatus;
    }

    /** 旧契约(小程序)响应体里的 {@code code},默认 500,与迁移前的 {@code error(msg)} 一致。 */
    public int getLegacyCode()
    {
        return legacyCode;
    }

    public Object getData()
    {
        return data;
    }

    /** 业务拒绝不需要堆栈:它是预期分支,不是故障。 */
    @Override
    public synchronized Throwable fillInStackTrace()
    {
        return this;
    }
}
