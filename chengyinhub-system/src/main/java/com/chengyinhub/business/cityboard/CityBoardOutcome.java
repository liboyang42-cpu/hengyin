package com.chengyinhub.business.cityboard;

import java.util.Map;

/** 一次 CITY 棋盘操作的结果。失败也带稳定 errorCode，供原 requestId 读回。 */
public final class CityBoardOutcome {

    public final int httpStatus;
    public final String errorCode;
    public final String message;
    public final Map<String, Object> data;

    public CityBoardOutcome(int httpStatus, String errorCode, String message, Map<String, Object> data) {
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.message = message;
        this.data = data;
    }

    public static CityBoardOutcome ok(Map<String, Object> data) {
        return new CityBoardOutcome(200, null, "操作成功", data);
    }

    public static CityBoardOutcome fail(int httpStatus, String errorCode, String message, Map<String, Object> data) {
        return new CityBoardOutcome(httpStatus, errorCode, message, data);
    }
}
