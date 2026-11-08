package com.chengyinhub.business.exception;

/**
 * 收入分配域的可区分拒绝原因。
 *
 * <p>理由码是调用方唯一能依赖的分类；不把内部规则细节或他人资金信息塞进 message。
 */
public class MarketingRevenueException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String reasonCode;
    private final int httpStatus;

    public MarketingRevenueException(String reasonCode, int httpStatus, String message) {
        super(message);
        this.reasonCode = reasonCode;
        this.httpStatus = httpStatus;
    }

    public String getReasonCode() { return reasonCode; }
    public int getHttpStatus() { return httpStatus; }

    public static MarketingRevenueException badRequest(String reasonCode, String message) {
        return new MarketingRevenueException(reasonCode, 400, message);
    }

    public static MarketingRevenueException forbidden(String reasonCode, String message) {
        return new MarketingRevenueException(reasonCode, 403, message);
    }

    public static MarketingRevenueException notFound(String reasonCode, String message) {
        return new MarketingRevenueException(reasonCode, 404, message);
    }

    public static MarketingRevenueException conflict(String reasonCode, String message) {
        return new MarketingRevenueException(reasonCode, 409, message);
    }
}
