package com.chengyinhub.business.exception;

public class FundActionPreflightException extends RuntimeException {
    private final String businessCode;
    public FundActionPreflightException(String businessCode, String message) {
        super(message);
        this.businessCode = businessCode;
    }
    public String getBusinessCode() { return businessCode; }
}
