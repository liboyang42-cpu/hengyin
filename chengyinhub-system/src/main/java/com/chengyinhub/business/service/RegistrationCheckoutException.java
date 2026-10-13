package com.chengyinhub.business.service;

/** 结算应用服务的可映射业务拒绝。 */
public class RegistrationCheckoutException extends RuntimeException {
    private final int code;

    public RegistrationCheckoutException(int code, String message) {
        super(message);
        this.code = code;
    }

    public RegistrationCheckoutException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
