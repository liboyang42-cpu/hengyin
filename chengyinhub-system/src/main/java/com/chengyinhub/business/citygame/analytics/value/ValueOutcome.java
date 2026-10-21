package com.chengyinhub.business.citygame.analytics.value;

import java.util.Map;

/** 价值统计的一次读结果。业务是否可计算看 data.status，不看 HTTP 包装。 */
public final class ValueOutcome {
    public final int httpStatus;
    public final String errorCode;
    public final String message;
    public final Map<String, Object> data;

    public ValueOutcome(int httpStatus, String errorCode, String message, Map<String, Object> data) {
        this.httpStatus = httpStatus;
        this.errorCode = errorCode;
        this.message = message;
        this.data = data;
    }

    public static ValueOutcome ok(Map<String, Object> data) {
        return new ValueOutcome(200, null, "已读取", data);
    }

    public static ValueOutcome fail(int httpStatus, String errorCode, String message, Map<String, Object> data) {
        return new ValueOutcome(httpStatus, errorCode, message, data);
    }
}
