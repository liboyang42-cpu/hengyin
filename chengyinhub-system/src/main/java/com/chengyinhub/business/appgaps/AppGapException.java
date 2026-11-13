package com.chengyinhub.business.appgaps;

/** An app-gaps failure carrying the HTTP status, envelope code and machine errorCode the app contracts read. */
public class AppGapException extends RuntimeException {
    private final int status;
    private final String errorCode;
    private final Object data;

    public AppGapException(int status, String errorCode, String message) { this(status, errorCode, message, null); }

    public AppGapException(int status, String errorCode, String message, Object data) {
        super(message);
        this.status = status;
        this.errorCode = errorCode;
        this.data = data;
    }

    public int getStatus() { return status; }
    public String getErrorCode() { return errorCode; }
    public Object getData() { return data; }

    public static AppGapException unauthorized() { return new AppGapException(401, "UNAUTHORIZED", "请先登录"); }
    public static AppGapException badRequest(String field) { return new AppGapException(400, "BAD_REQUEST", field + " invalid"); }
    public static AppGapException notFound(String code) { return new AppGapException(404, code, "不存在"); }
    public static AppGapException conflict(String code, String message) { return new AppGapException(409, code, message); }
}
