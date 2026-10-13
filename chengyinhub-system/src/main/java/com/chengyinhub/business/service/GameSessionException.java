package com.chengyinhub.business.service;

/** 带稳定前端 reasonCode 的游戏局业务异常。 */
public class GameSessionException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String errorCode;

    public GameSessionException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() { return errorCode; }
}
