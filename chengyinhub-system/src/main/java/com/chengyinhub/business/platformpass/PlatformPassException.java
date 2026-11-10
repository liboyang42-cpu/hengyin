package com.chengyinhub.business.platformpass;

/** 通行证业务拒绝：reasonCode 给客户端分支，message 给用户看。 */
public class PlatformPassException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String reasonCode;
    private final int httpStatus;

    public PlatformPassException(String reasonCode, int httpStatus, String message) {
        super(message);
        this.reasonCode = reasonCode;
        this.httpStatus = httpStatus;
    }

    public String getReasonCode() { return reasonCode; }
    public int getHttpStatus() { return httpStatus; }

    public static PlatformPassException badRequest(String code, String message) { return new PlatformPassException(code, 400, message); }
    public static PlatformPassException notFound(String code, String message) { return new PlatformPassException(code, 404, message); }
    public static PlatformPassException conflict(String code, String message) { return new PlatformPassException(code, 409, message); }
    public static PlatformPassException unavailable(String code, String message) { return new PlatformPassException(code, 503, message); }
}
