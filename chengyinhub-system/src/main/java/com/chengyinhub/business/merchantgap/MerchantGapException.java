package com.chengyinhub.business.merchantgap;

/**
 * A refusal of a merchant-gap route. The controller answers with HTTP status == {@code status}, {@code code} == status,
 * {@code msg} and {@code errorCode}, so the app sees the same status in the transport and in the envelope.
 */
public class MerchantGapException extends RuntimeException {
    private final int status;
    private final String errorCode;
    private final Object data;

    public MerchantGapException(int status, String errorCode, String message) {
        this(status, errorCode, message, null);
    }

    public MerchantGapException(int status, String errorCode, String message, Object data) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.data = data;
    }

    public int getStatus() { return status; }
    public String getErrorCode() { return errorCode; }
    public Object getData() { return data; }
}
