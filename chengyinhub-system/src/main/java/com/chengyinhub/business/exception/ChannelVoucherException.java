package com.chengyinhub.business.exception;

/**
 * 渠道码域拒绝。{@link #reasonCode} 是客户端分支依据；HTTP 状态码由控制器映射。
 * 文案不包含他人订单、码明文或渠道凭据。
 */
public class ChannelVoucherException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String reasonCode;
    private final int httpStatus;

    public ChannelVoucherException(String reasonCode, String message, int httpStatus) {
        super(message);
        this.reasonCode = reasonCode;
        this.httpStatus = httpStatus;
    }

    public static ChannelVoucherException badRequest(String reasonCode, String message) {
        return new ChannelVoucherException(reasonCode, message, 400);
    }

    public static ChannelVoucherException unauthorized(String reasonCode, String message) {
        return new ChannelVoucherException(reasonCode, message, 401);
    }

    public static ChannelVoucherException forbidden(String reasonCode, String message) {
        return new ChannelVoucherException(reasonCode, message, 403);
    }

    public static ChannelVoucherException notFound(String reasonCode, String message) {
        return new ChannelVoucherException(reasonCode, message, 404);
    }

    public static ChannelVoucherException conflict(String reasonCode, String message) {
        return new ChannelVoucherException(reasonCode, message, 409);
    }

    public static ChannelVoucherException unavailable(String reasonCode, String message) {
        return new ChannelVoucherException(reasonCode, message, 503);
    }

    public String getReasonCode() { return reasonCode; }
    public int getHttpStatus() { return httpStatus; }
}
