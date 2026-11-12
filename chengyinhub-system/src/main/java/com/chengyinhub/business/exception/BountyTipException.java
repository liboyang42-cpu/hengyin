package com.chengyinhub.business.exception;

/**
 * 视频悬赏与主持小费的可区分拒绝原因。
 *
 * <p>理由码是客户端唯一能依赖的分类。特别地,{@code CHANNEL_NOT_ENABLED} 表示
 * 收款通道未接入 —— 这是<b>明确未启用</b>,不是"支付失败",更不得被 UI 显示成已付款。
 *
 * @author chengyinhub
 */
public class BountyTipException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** 收款/打款通道未启用(外部约束),不得伪造收款。 */
    public static final int CHANNEL_NOT_ENABLED = 501;

    private final String reasonCode;
    private final int httpStatus;

    public BountyTipException(String reasonCode, int httpStatus, String message) {
        super(message);
        this.reasonCode = reasonCode;
        this.httpStatus = httpStatus;
    }

    public String getReasonCode() { return reasonCode; }
    public int getHttpStatus() { return httpStatus; }

    public static BountyTipException badRequest(String reasonCode, String message) {
        return new BountyTipException(reasonCode, 400, message);
    }

    public static BountyTipException unauthorized(String reasonCode, String message) {
        return new BountyTipException(reasonCode, 401, message);
    }

    public static BountyTipException forbidden(String reasonCode, String message) {
        return new BountyTipException(reasonCode, 403, message);
    }

    public static BountyTipException notFound(String reasonCode, String message) {
        return new BountyTipException(reasonCode, 404, message);
    }

    public static BountyTipException conflict(String reasonCode, String message) {
        return new BountyTipException(reasonCode, 409, message);
    }

    /**
     * 通道未启用。用 {@link #CHANNEL_NOT_ENABLED} 而非 5xx"失败",
     * 让调用方能把"没开"与"出错"分开处理。
     */
    public static BountyTipException channelNotEnabled(String message) {
        return new BountyTipException("CHANNEL_NOT_ENABLED", CHANNEL_NOT_ENABLED, message);
    }
}
