package com.chengyinhub.business.exception;

public class MerchantClaimException extends RuntimeException {
    private final String businessCode;
    private final int httpCode;
    public MerchantClaimException(String businessCode, String message, int httpCode) {
        super(message);
        this.businessCode = businessCode;
        this.httpCode = httpCode;
    }
    public String getBusinessCode() { return businessCode; }
    public int getHttpCode() { return httpCode; }
}
