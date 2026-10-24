package com.chengyinhub.business.service.support;

/** Doc2 F1a 定向会话的业务拒绝:err 为机器码,httpCode 进 AjaxResult.code,data 可带当前视图/原回执。 */
public class OrientSessionException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String err;
    private final int httpCode;
    private final transient Object data;

    public OrientSessionException(String err, int httpCode, String message) {
        this(err, httpCode, message, null);
    }

    public OrientSessionException(String err, int httpCode, String message, Object data) {
        super(message);
        this.err = err;
        this.httpCode = httpCode;
        this.data = data;
    }

    public String getErr() { return err; }
    public int getHttpCode() { return httpCode; }
    public Object getData() { return data; }
}
