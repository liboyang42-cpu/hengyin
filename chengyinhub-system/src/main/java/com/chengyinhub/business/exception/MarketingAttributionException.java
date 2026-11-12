package com.chengyinhub.business.exception;

/**
 * 归因/推广凭据的可区分拒绝原因。
 *
 * <p>理由码是客户端唯一能依赖的分类(客户端不得靠文案猜测),同时刻意不给出
 * "凭据存在但未绑定该单"之类的内部细节。
 */
public class MarketingAttributionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String reasonCode;
    private final int httpStatus;

    public MarketingAttributionException(String reasonCode, int httpStatus, String message) {
        super(message);
        this.reasonCode = reasonCode;
        this.httpStatus = httpStatus;
    }

    public String getReasonCode() { return reasonCode; }
    public int getHttpStatus() { return httpStatus; }

    public static MarketingAttributionException badRequest(String reasonCode, String message) {
        return new MarketingAttributionException(reasonCode, 400, message);
    }

    public static MarketingAttributionException forbidden(String reasonCode, String message) {
        return new MarketingAttributionException(reasonCode, 403, message);
    }

    public static MarketingAttributionException notFound(String reasonCode, String message) {
        return new MarketingAttributionException(reasonCode, 404, message);
    }

    public static MarketingAttributionException conflict(String reasonCode, String message) {
        return new MarketingAttributionException(reasonCode, 409, message);
    }
}
