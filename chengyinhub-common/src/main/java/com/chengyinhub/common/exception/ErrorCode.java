package com.chengyinhub.common.exception;

/**
 * 错误码目录(R5 error-contract / BE-14、BE-05)。
 *
 * <p>稳定的字符串错误码,按领域分组(前缀 = 领域)。码值一经发布不得改名或复用:
 * App 与监控按码值做判定。新增码只在本枚举里加。
 *
 * <ul>
 *   <li>{@link #code()} — 线上稳定码值,例如 {@code AUTH.SIGN_IN_REQUIRED};</li>
 *   <li>{@link #messageKey()} — 客户端本地化文案键;</li>
 *   <li>{@link #httpStatus()} — 选择新契约({@code X-Error-Contract: 2})时建议的 HTTP 状态;</li>
 *   <li>{@link #defaultMessage()} — 未提供业务文案时的中文兜底。</li>
 * </ul>
 *
 * <p>兼容:不带新请求头时,响应体里的 {@code code} 仍是迁移前的值(默认 500),不出现 {@code errorCode},
 * HTTP 仍是 200。小程序看到的形状与迁移前逐字节一致。
 */
public enum ErrorCode
{
    // ---- 通用 ----
    BAD_REQUEST("COMMON.BAD_REQUEST", "error.common.badRequest", 400, "参数有误"),
    NOT_FOUND("COMMON.NOT_FOUND", "error.common.notFound", 404, "内容不存在"),
    FORBIDDEN("COMMON.FORBIDDEN", "error.common.forbidden", 403, "无权操作"),
    CONFLICT("COMMON.CONFLICT", "error.common.conflict", 409, "状态已变化，请刷新后重试"),
    METHOD_NOT_ALLOWED("COMMON.METHOD_NOT_ALLOWED", "error.common.badRequest", 405, "请求方式不支持"),
    GONE("COMMON.GONE", "error.common.gone", 410, "内容已失效"),
    PAYLOAD_TOO_LARGE("COMMON.PAYLOAD_TOO_LARGE", "error.common.payloadTooLarge", 413, "内容过大"),
    REFUSED("COMMON.REFUSED", "error.common.refused", 422, "当前无法完成该操作"),
    UPGRADE_REQUIRED("COMMON.UPGRADE_REQUIRED", "error.common.upgradeRequired", 426, "请升级到最新版本"),
    TOO_MANY_REQUESTS("COMMON.TOO_MANY_REQUESTS", "error.common.tooManyRequests", 429, "操作太频繁，请稍后再试"),
    FEATURE_OFF("COMMON.FEATURE_OFF", "error.common.featureOff", 403, "该功能暂未开放"),
    /** 未迁移的 {@code error(msg)}:按文案推断为业务拒绝。监控据此统计剩余未迁移点。 */
    UNTYPED_REFUSAL("COMMON.UNTYPED_REFUSAL", "error.common.refused", 422, "当前无法完成该操作"),

    // ---- 登录 / 账号 ----
    SIGN_IN_REQUIRED("AUTH.SIGN_IN_REQUIRED", "error.auth.signInRequired", 401, "请先登录"),
    SESSION_EXPIRED("AUTH.SESSION_EXPIRED", "error.auth.sessionExpired", 401, "登录状态已失效，请重新登录"),
    ACCOUNT_DISABLED("AUTH.ACCOUNT_DISABLED", "error.auth.accountDisabled", 403, "会员已禁用，请联系管理员"),
    USER_NOT_FOUND("AUTH.USER_NOT_FOUND", "error.auth.userNotFound", 404, "用户不存在"),
    SIGN_IN_REJECTED("AUTH.SIGN_IN_REJECTED", "error.auth.signInRejected", 401, "登录失败"),
    VERIFICATION_CODE_INVALID("AUTH.VERIFICATION_CODE_INVALID", "error.auth.codeInvalid", 400, "验证码错误"),

    // ---- 漫游 / 迷雾 ----
    LOCATION_REQUIRED("ROAM.LOCATION_REQUIRED", "error.roam.locationRequired", 400, "缺少定位"),
    LOCATION_INVALID("ROAM.LOCATION_INVALID", "error.roam.locationInvalid", 400, "定位无效"),
    ROAM_SESSION_NOT_FOUND("ROAM.SESSION_NOT_FOUND", "error.roam.sessionNotFound", 404, "会话不存在"),
    ROAM_ALREADY_SETTLED("ROAM.ALREADY_SETTLED", "error.roam.alreadySettled", 409, "本次漫游已结算"),
    ROAM_PLACE_NOT_FOUND("ROAM.PLACE_NOT_FOUND", "error.roam.placeNotFound", 404, "地点不存在或已下线"),
    ROAM_TOO_FAR("ROAM.TOO_FAR", "error.roam.tooFar", 422, "还没到该地点附近"),
    ROAM_MOVING_TOO_FAST("ROAM.MOVING_TOO_FAST", "error.roam.movingTooFast", 422, "移动过快,疑似伪造定位"),
    ROAM_AREA_TOO_LARGE("ROAM.AREA_TOO_LARGE", "error.roam.areaTooLarge", 400, "范围过大，请放大地图后再看"),

    // ---- 玩法 / 主题 ----
    TOPIC_NOT_FOUND("PLAY.TOPIC_NOT_FOUND", "error.play.topicNotFound", 404, "主题不存在"),
    TEMPLATE_NOT_FOUND("PLAY.TEMPLATE_NOT_FOUND", "error.play.templateNotFound", 404, "模板不存在"),
    TOPIC_FORBIDDEN("PLAY.TOPIC_FORBIDDEN", "error.play.topicForbidden", 403, "无权操作该主题"),
    OFFICIAL_PUBLISH_FORBIDDEN("PLAY.OFFICIAL_PUBLISH_FORBIDDEN", "error.play.officialPublishForbidden", 403, "无官方发布权限"),

    // ---- 俱乐部 ----
    CLUB_NOT_FOUND("CLUB.NOT_FOUND", "error.club.notFound", 404, "俱乐部不存在"),
    CLUB_MEMBERS_ONLY("CLUB.MEMBERS_ONLY", "error.club.membersOnly", 403, "这个俱乐部只对成员开放"),
    CLUB_POST_NOT_FOUND("CLUB.POST_NOT_FOUND", "error.club.postNotFound", 404, "动态不存在"),
    CLUB_INVITE_NOT_FOUND("CLUB.INVITE_NOT_FOUND", "error.club.inviteNotFound", 404, "邀请不存在"),

    // ---- 活动 / 票务 ----
    ACTIVITY_NOT_FOUND("TICKET.ACTIVITY_NOT_FOUND", "error.ticket.activityNotFound", 404, "活动不存在"),
    ACTIVITY_FORBIDDEN("TICKET.ACTIVITY_FORBIDDEN", "error.ticket.activityForbidden", 403, "无权操作该活动"),
    REGISTRATION_NOT_FOUND("TICKET.REGISTRATION_NOT_FOUND", "error.ticket.registrationNotFound", 404, "报名不存在"),
    COUPON_NOT_FOUND("TICKET.COUPON_NOT_FOUND", "error.ticket.couponNotFound", 404, "该优惠券不存在"),

    // ---- 商家 / 核销 ----
    MERCHANT_NOT_FOUND("MERCHANT.NOT_FOUND", "error.merchant.notFound", 404, "商家不存在"),
    MERCHANT_APPLICATION_REQUIRED("MERCHANT.APPLICATION_REQUIRED", "error.merchant.applicationRequired", 403, "请先提交商家入驻申请"),
    MERCHANT_ONLY("MERCHANT.ONLY", "error.merchant.only", 403, "商家才能报名"),
    REDEMPTION_REFUSED("MERCHANT.REDEMPTION_REFUSED", "error.merchant.redemptionRefused", 422, "核销未通过"),

    // ---- 系统(真正的失败,5xx) ----
    SYSTEM_FAILURE("SYSTEM.FAILURE", "error.system.failure", 500, "系统繁忙，请稍后重试"),
    /** 未迁移的 {@code error(msg)}:按文案推断为系统失败(兜底 catch 块)。 */
    UNTYPED_FAILURE("SYSTEM.UNTYPED_FAILURE", "error.system.failure", 500, "系统繁忙，请稍后重试"),
    NOT_IMPLEMENTED("SYSTEM.NOT_IMPLEMENTED", "error.system.notImplemented", 501, "该功能暂不可用"),
    BAD_GATEWAY("SYSTEM.BAD_GATEWAY", "error.system.unavailable", 502, "服务暂不可用，请稍后重试"),
    UNAVAILABLE("SYSTEM.UNAVAILABLE", "error.system.unavailable", 503, "服务暂不可用，请稍后重试");

    private final String code;
    private final String messageKey;
    private final int httpStatus;
    private final String defaultMessage;

    ErrorCode(String code, String messageKey, int httpStatus, String defaultMessage)
    {
        this.code = code;
        this.messageKey = messageKey;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public String code()
    {
        return code;
    }

    public String messageKey()
    {
        return messageKey;
    }

    public int httpStatus()
    {
        return httpStatus;
    }

    public String defaultMessage()
    {
        return defaultMessage;
    }

    /** 拒绝(4xx):客户端不应自动重试。 */
    public boolean isRefusal()
    {
        return httpStatus >= 400 && httpStatus < 500;
    }

    /** 按线上码值反查;未知返回 null。 */
    public static ErrorCode fromCode(String code)
    {
        if (code == null)
        {
            return null;
        }
        for (ErrorCode value : values())
        {
            if (value.code.equals(code))
            {
                return value;
            }
        }
        return null;
    }
}
