package com.chengyinhub.business.service.impl;

import com.chengyinhub.common.constant.HttpStatus;
import com.chengyinhub.common.exception.ServiceException;
import com.chengyinhub.system.service.ISysConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** 全局会费功能闸；配置缺失、异常或非严格 true 时都关闭。 */
@Component
public class ClubMembershipFeatureGate {
    static final String CONFIG_KEY = "feature.flag.clubMembership";

    @Autowired private ISysConfigService configService;

    public boolean isEnabled() {
        try {
            String value = configService.selectConfigByKey(CONFIG_KEY);
            return value != null && "true".equalsIgnoreCase(value.trim());
        } catch (Exception ignored) {
            return false;
        }
    }

    public void requireEnabled() {
        if (!isEnabled()) {
            throw new ServiceException("俱乐部付费会员功能暂未开放", HttpStatus.NOT_FOUND);
        }
    }
}
