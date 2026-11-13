package com.chengyinhub.business.citygame;

/** A CITY failure with the HTTP status, envelope code and errorCode the app contracts read. */
public class CityGameException extends RuntimeException {
    private final int status;
    private final String errorCode;
    private final Object data;

    public CityGameException(int status, String errorCode, String message) { this(status, errorCode, message, null); }

    public CityGameException(int status, String errorCode, String message, Object data) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.data = data;
    }

    public int getStatus() { return status; }
    public String getErrorCode() { return errorCode; }
    public Object getData() { return data; }
}
