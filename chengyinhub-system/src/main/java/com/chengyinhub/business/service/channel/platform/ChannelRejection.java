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

    /** 唯一键冲突：Spring 翻译后的 DuplicateKeyException 与裸 MyBatis 的约束异常都认。 */
    public static boolean isDuplicateKey(Throwable error)
    {
        for (Throwable t = error; t != null; t = t.getCause())
        {
            if (t instanceof org.springframework.dao.DuplicateKeyException) return true;
            if (t instanceof java.sql.SQLIntegrityConstraintViolationException v && v.getErrorCode() == 1062) return true;
            if (t.getCause() == t) break;
        }
        return false;
    }

    /** MyBatis 的 Map 行里 DATETIME 可能是 LocalDateTime 或 Timestamp；统一成 Date。 */
    public static java.util.Date toDate(Object value)
    {
        if (value instanceof java.time.LocalDateTime t) return java.sql.Timestamp.valueOf(t);
        if (value instanceof java.util.Date d) return d;
        return null;
    }

    public static ChannelRejection badField(String message) { return new ChannelRejection(400, "INVALID_FIELD", message); }
    public static ChannelRejection notFound() { return new ChannelRejection(404, "CHANNEL_NOT_FOUND", "频道不存在"); }
    public static ChannelRejection forbidden() { return new ChannelRejection(403, "CHANNEL_FORBIDDEN", "无权操作该频道"); }
}
