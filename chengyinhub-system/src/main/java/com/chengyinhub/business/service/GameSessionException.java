package com.chengyinhub.business.service;

/** 带稳定前端 reasonCode 的游戏局业务异常。 */
public class GameSessionException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    /** 站点暂停/恢复没有改变任何状态(SC-CITY-RESUME-RACE):回执记 FAILED,控制器不写第二条操作日志。 */
    public static final String STATION_ALREADY_PAUSED = "GAME_STATION_ALREADY_PAUSED";
    public static final String STATION_ALREADY_ACTIVE = "GAME_STATION_ALREADY_ACTIVE";
    private final String errorCode;

    public GameSessionException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() { return errorCode; }

    public static boolean isNoChange(String errorCode) {
        return STATION_ALREADY_PAUSED.equals(errorCode) || STATION_ALREADY_ACTIVE.equals(errorCode);
    }
}
