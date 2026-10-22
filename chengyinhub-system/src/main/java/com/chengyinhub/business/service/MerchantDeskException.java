package com.chengyinhub.business.service;

/**
 * Doc2 F4 商家工作台业务异常:带 HTTP 语义码与稳定 errorCode,控制器原样转成
 * {@code AjaxResult(code, msg).put("errorCode", errorCode)}。
 */
public class MerchantDeskException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public static final String DISABLED = "F4_DISABLED";
    public static final String BAD_REQUEST = "F4_BAD_REQUEST";
    public static final String NOT_FOUND = "F4_NOT_FOUND";
    public static final String NOT_OWNER = "F4_NOT_OWNER";
    public static final String IDEMPOTENCY_CONFLICT = "F4_IDEMPOTENCY_CONFLICT";
    public static final String VERSION_CONFLICT = "F4_VERSION_CONFLICT";
    public static final String PRODUCT_LIVE_LOCKED = "F4_PRODUCT_LIVE_LOCKED";
    public static final String REVIEW_CLOSED = "F4_REVIEW_CLOSED";
    public static final String EVIDENCE_LIMIT = "F4_EVIDENCE_LIMIT";
    public static final String STORE_NOT_ACTIVE = "F4_STORE_NOT_ACTIVE";

    private final int httpCode;
    private final String errorCode;

    public MerchantDeskException(int httpCode, String errorCode, String message) {
        super(message);
        this.httpCode = httpCode;
        this.errorCode = errorCode;
    }

    public int getHttpCode() { return httpCode; }

    public String getErrorCode() { return errorCode; }
}
