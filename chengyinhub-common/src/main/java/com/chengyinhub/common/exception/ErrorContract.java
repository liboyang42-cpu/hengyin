package com.chengyinhub.common.exception;

import java.util.regex.Pattern;

/**
 * 错误契约 v2 的唯一判据(R5 error-contract)。纯函数,无 Spring 依赖,便于单测。
 *
 * <p>契约按请求选择:只有带 {@value #HEADER}: {@value #VERSION_2} 的请求才得到
 * HTTP 4xx/5xx 与 {@code errorCode};其它请求(小程序、旧 App、管理后台)一字不变。
 */
public final class ErrorContract
{
    /** 选择新契约的请求头。 */
    public static final String HEADER = "X-Error-Contract";

    /** 当前唯一的新契约版本。 */
    public static final String VERSION_2 = "2";

    /** v2 响应体里新增的字段名。 */
    public static final String ERROR_CODE_TAG = "errorCode";

    /** 迁移前 {@code error(msg)} 的体内 code。 */
    public static final int LEGACY_ERROR_CODE = 500;

    private static final int SUCCESS = 200;
    private static final int WARN = 601;

    /**
     * 未迁移的 {@code error(msg)}(体内 500、无错误码)按文案推断:兜底 catch 块的文案
     * (系统繁忙/稍后重试/xx失败/暂不可用…)视为系统失败,其余视为业务拒绝。
     */
    private static final Pattern FAILURE_WORDING = Pattern.compile(
            "稍后|繁忙|失败|异常|暂不可用|不可用|超时|回读|网络|服务出错|系统错误");

    private ErrorContract()
    {
    }

    /** 请求头值是否选择了 v2。容忍首尾空白。 */
    public static boolean isV2(String headerValue)
    {
        return headerValue != null && VERSION_2.equals(headerValue.trim());
    }

    /** v2 下一个响应应有的 HTTP 状态与错误码。 */
    public record Decision(int httpStatus, ErrorCode errorCode)
    {
        public String code()
        {
            return errorCode.code();
        }
    }

    /**
     * 判定。
     *
     * @param bodyCode 响应体里的 {@code code}
     * @param msg 响应体里的 {@code msg}
     * @param explicit 代码里显式给出的错误码(可空)
     * @param explicitHttp 显式给出的 HTTP 状态(可空,空则取错误码建议值)
     * @return 成功/警告返回 null(不改动),否则返回判定
     */
    public static Decision decide(int bodyCode, String msg, ErrorCode explicit, Integer explicitHttp)
    {
        if (bodyCode == SUCCESS || bodyCode == WARN || (bodyCode >= 200 && bodyCode < 300))
        {
            return null;
        }
        if (explicit != null)
        {
            return new Decision(explicitHttp != null ? explicitHttp : explicit.httpStatus(), explicit);
        }
        ErrorCode inferred = fromBodyCode(bodyCode, msg);
        int http = bodyCode >= 400 && bodyCode < 600 && bodyCode != LEGACY_ERROR_CODE ? bodyCode : inferred.httpStatus();
        return new Decision(http, inferred);
    }

    private static ErrorCode fromBodyCode(int bodyCode, String msg)
    {
        switch (bodyCode)
        {
            case 400: return ErrorCode.BAD_REQUEST;
            case 401: return ErrorCode.SIGN_IN_REQUIRED;
            case 403: return ErrorCode.FORBIDDEN;
            case 404: return ErrorCode.NOT_FOUND;
            case 405: return ErrorCode.METHOD_NOT_ALLOWED;
            case 409: return ErrorCode.CONFLICT;
            case 410: return ErrorCode.GONE;
            case 413: return ErrorCode.PAYLOAD_TOO_LARGE;
            case 426: return ErrorCode.UPGRADE_REQUIRED;
            case 429: return ErrorCode.TOO_MANY_REQUESTS;
            case 501: return ErrorCode.NOT_IMPLEMENTED;
            case 502: return ErrorCode.BAD_GATEWAY;
            case 503: return ErrorCode.UNAVAILABLE;
            case LEGACY_ERROR_CODE: return looksLikeFailure(msg) ? ErrorCode.UNTYPED_FAILURE : ErrorCode.UNTYPED_REFUSAL;
            default:
                if (bodyCode >= 400 && bodyCode < 500) return ErrorCode.REFUSED;
                if (bodyCode >= 500 && bodyCode < 600) return ErrorCode.SYSTEM_FAILURE;
                // 非 HTTP 形状的私有业务码(如 520 以外的四位数):按业务拒绝处理。
                return ErrorCode.REFUSED;
        }
    }

    public static boolean looksLikeFailure(String msg)
    {
        return msg == null || msg.trim().isEmpty() || FAILURE_WORDING.matcher(msg).find();
    }
}
