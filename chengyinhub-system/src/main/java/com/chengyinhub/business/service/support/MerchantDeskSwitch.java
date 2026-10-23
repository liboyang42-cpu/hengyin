package com.chengyinhub.business.service.support;

import com.chengyinhub.business.service.MerchantDeskException;
import com.chengyinhub.system.service.ISysConfigService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Doc2 F4 商家工作台总开关 sys_config['merchant.desk.f4.enabled']。
 * 配置缺失、读取异常或非严格 true 时一律关闭:关闭态下 /api/merchant/desk/* 不读不写任何新表。
 */
@Component
public class MerchantDeskSwitch {
    public static final String CONFIG_KEY = "merchant.desk.f4.enabled";

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
            throw new MerchantDeskException(404, MerchantDeskException.DISABLED, "商家工作台新功能暂未开放");
        }
    }
}
