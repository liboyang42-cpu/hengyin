package com.chengyinhub.business.service;

/** 结算应用服务的可映射业务拒绝。 */
public class RegistrationCheckoutException extends RuntimeException {
    private final int code;
    /** 可选的稳定机器码（如未成年挂点的 MINOR_*）；既有拒绝为 null。 */
    private final String errorCode;

    public RegistrationCheckoutException(int code, String message) {
        this(code, message, (String) null);
    }

    public RegistrationCheckoutException(int code, String message, String errorCode) {
        super(message);
        this.code = code;
        this.errorCode = errorCode;
    }

    public RegistrationCheckoutException(int code, String message, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.errorCode = null;
    }

    public int getCode() {
        return code;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
