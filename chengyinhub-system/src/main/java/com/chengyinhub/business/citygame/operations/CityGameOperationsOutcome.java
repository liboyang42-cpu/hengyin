package com.chengyinhub.business.citygame.operations;

import java.util.Map;

/** 一次运营命令的结果。失败也写入审计，供原 requestId 读回。 */
public final class CityGameOperationsOutcome {

    public final int httpStatus;
    public final String errorCode;
    public final String message;
    public final Map<String, Object> data;

    public CityGameOperationsOutcome(int httpStatus, String errorCode, String message, Map<String, Object> data) {
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.message = message;
        this.data = data;
    }

    public static CityGameOperationsOutcome ok(Map<String, Object> data) {
        return new CityGameOperationsOutcome(200, null, "操作成功", data);
    }

    public static CityGameOperationsOutcome fail(int httpStatus, String errorCode, String message, Map<String, Object> data) {
        return new CityGameOperationsOutcome(httpStatus, errorCode, message, data);
    }
}
