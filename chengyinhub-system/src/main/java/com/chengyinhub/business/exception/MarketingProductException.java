package com.chengyinhub.business.exception;

/**
 * 商品/授权可区分拒绝原因。理由码是客户端唯一可依赖的分类，不靠人工文案猜。
 */
public class MarketingProductException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String reasonCode;
    private final int httpStatus;
    /** 客户端可用的结构化补充（如 route=2 的原结算跳转参数）；默认 null。 */
    private java.util.Map<String, Object> data;

    public MarketingProductException(String reasonCode, int httpStatus, String message) {
        super(message);
        this.reasonCode = reasonCode;
        this.httpStatus = httpStatus;
    }

    public String getReasonCode() { return reasonCode; }
    public int getHttpStatus() { return httpStatus; }
    public java.util.Map<String, Object> getData() { return data; }

    public MarketingProductException withData(java.util.Map<String, Object> payload) {
        this.data = payload;
        return this;
    }

    public static MarketingProductException badRequest(String reasonCode, String message) {
        return new MarketingProductException(reasonCode, 400, message);
    }

    public static MarketingProductException unauthorized() {
        return new MarketingProductException("LOGIN_REQUIRED", 401, "请先登录");
    }

    public static MarketingProductException forbidden(String reasonCode, String message) {
        return new MarketingProductException(reasonCode, 403, message);
    }

    public static MarketingProductException notFound(String reasonCode, String message) {
        return new MarketingProductException(reasonCode, 404, message);
    }

    public static MarketingProductException conflict(String reasonCode, String message) {
        return new MarketingProductException(reasonCode, 409, message);
    }

    public static MarketingProductException paymentRequired(String reasonCode, String message) {
        return new MarketingProductException(reasonCode, 402, message);
    }
}
