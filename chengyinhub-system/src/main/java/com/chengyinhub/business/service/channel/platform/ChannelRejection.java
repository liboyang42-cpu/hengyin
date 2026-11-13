package com.chengyinhub.business.service.channel.platform;

/**
 * 频道合同的确定性业务拒绝：HTTP 状态与 body.code 相同，errorCode 为 App 可识别的稳定码。
 * 存储/提交不确定不走这里（控制器统一映射 500 + OUTCOME_UNKNOWN）。
 */
public class ChannelRejection extends RuntimeException
{
    private static final long serialVersionUID = 1L;

    private final int status;
    private final String errorCode;

    public ChannelRejection(int status, String errorCode, String message)
    {
        super(message);
        if (status < 400 || status > 499) throw new IllegalArgumentException("Rejection must be 4xx");
        if (errorCode == null || !errorCode.matches("[A-Z][A-Z0-9_]{0,127}")) throw new IllegalArgumentException("Invalid error code");
        this.status = status;
        this.errorCode = errorCode;
    }

    public int getStatus() { return status; }
    public String getErrorCode() { return errorCode; }

    public static ChannelRejection badField(String message) { return new ChannelRejection(400, "INVALID_FIELD", message); }
    public static ChannelRejection notFound() { return new ChannelRejection(404, "CHANNEL_NOT_FOUND", "频道不存在"); }
    public static ChannelRejection forbidden() { return new ChannelRejection(403, "CHANNEL_FORBIDDEN", "无权操作该频道"); }
}
