package com.chengyinhub.common.exception;

import java.util.Set;

/**
 * 业务异常
 * 
 * @author ruoyi
 */
public final class ServiceException extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    /**
     * 允许随 ServiceException 下发的业务码白名单(按 HTTP 语义取值)。
     * 由 ServiceExceptionCodeWhitelistContractTest 扫描全部 {@code new ServiceException(msg, code)}
     * 的字面量/HttpStatus 常量逐一校验;新增码值先在这里登记并说明语义。
     * 不带 code 的构造(code 为 null)由各调用方兜底(多数回 500),见 {@link #ServiceException(String)}。
     */
    public static final Set<Integer> ALLOWED_CODES = Set.of(
            400, 401, 403, 404, 409, 410, 413, 422, 426, 429, 500, 501, 503);

    /**
     * 错误码
     */
    private Integer code;

    /**
     * 错误提示
     */
    private String message;

    /**
     * 错误明细，内部调试错误
     *
     * 和 {@link CommonResult#getDetailMessage()} 一致的设计
     */
    private String detailMessage;

    /**
     * 随错误一起下发的当前世界(R14-R18 回执化):冲突时让客户端免再一次 GET 就能重同步。
     * 与 {@link com.chengyinhub.common.core.domain.AjaxResult#DATA_TAG} 同键输出,普通业务错误为 null。
     */
    private Object data;

    /**
     * 空构造方法，避免反序列化问题
     */
    public ServiceException()
    {
    }

    /**
     * 不带业务码:code 为 null,由捕获方决定兜底码(通常 500)。
     * 可预期的业务失败(参数错/无权/冲突/不存在)请用 {@link #ServiceException(String, Integer)} 显式给出白名单内的码。
     */
    public ServiceException(String message)
    {
        this.message = message;
    }

    public ServiceException(String message, Integer code)
    {
        this.message = message;
        this.code = code;
    }

    public String getDetailMessage()
    {
        return detailMessage;
    }

    public Object getData()
    {
        return data;
    }

    public ServiceException setData(Object data)
    {
        this.data = data;
        return this;
    }

    @Override
    public String getMessage()
    {
        return message;
    }

    public Integer getCode()
    {
        return code;
    }

    public ServiceException setMessage(String message)
    {
        this.message = message;
        return this;
    }

    public ServiceException setDetailMessage(String detailMessage)
    {
        this.detailMessage = detailMessage;
        return this;
    }
}