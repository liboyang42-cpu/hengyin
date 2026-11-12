package com.chengyinhub.business.exception;

/**
 * 促销配置/资格的可区分拒绝原因。
 *
 * <p>理由码是客户端与后台唯一能依赖的分类(不得靠文案猜测)。刻意不区分
 * "配置存在但没有资格"与"配置不存在"的内部细节以外的信息:资格失败只回原因码。
 */
public class MarketingPromotionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String reasonCode;
    private final int httpStatus;

    public MarketingPromotionException(String reasonCode, int httpStatus, String message) {
        super(message);
        this.reasonCode = reasonCode;
        this.httpStatus = httpStatus;
    }

    public String getReasonCode() { return reasonCode; }
    public int getHttpStatus() { return httpStatus; }

    public static MarketingPromotionException badRequest(String reasonCode, String message) {
        return new MarketingPromotionException(reasonCode, 400, message);
    }

    public static MarketingPromotionException forbidden(String reasonCode, String message) {
        return new MarketingPromotionException(reasonCode, 403, message);
    }

    public static MarketingPromotionException notFound(String reasonCode, String message) {
        return new MarketingPromotionException(reasonCode, 404, message);
    }

    public static MarketingPromotionException conflict(String reasonCode, String message) {
        return new MarketingPromotionException(reasonCode, 409, message);
    }
}
