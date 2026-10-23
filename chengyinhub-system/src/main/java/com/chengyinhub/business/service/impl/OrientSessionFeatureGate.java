package com.chengyinhub.business.service.impl;

import com.chengyinhub.system.service.ISysConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Doc2 F1a 定向会话总开关;配置缺失、异常或非严格 true 时都关闭。 */
@Component
public class OrientSessionFeatureGate {
    static final String CONFIG_KEY = "feature.flag.orientSession";

    @Autowired private ISysConfigService configService;

    public boolean isEnabled() {
        try {
            String value = configService.selectConfigByKey(CONFIG_KEY);
            return value != null && "true".equalsIgnoreCase(value.trim());
        } catch (Exception ignored) {
            return false;
        }
    }
}
