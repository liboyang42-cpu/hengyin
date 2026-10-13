package com.chengyinhub.business.service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** 报名结算应用服务返回值；Controller 只负责映射到 AjaxResult。 */
public final class RegistrationCheckoutResult {
    private final String message;
    private final Map<String, Object> data;

    public RegistrationCheckoutResult(String message, Map<String, Object> data) {
        this.message = message;
        this.data = Collections.unmodifiableMap(new LinkedHashMap<>(data));
    }

    public String getMessage() {
        return message;
    }

    public Map<String, Object> getData() {
        return data;
    }
}
