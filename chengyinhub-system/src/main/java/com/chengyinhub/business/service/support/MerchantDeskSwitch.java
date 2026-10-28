package com.chengyinhub.business.service.support;

import com.chengyinhub.business.service.MerchantDeskException;
import com.chengyinhub.business.service.compliance.ComplianceFeatureSwitches;
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
    /**
     * D2(2026-10-27 用户决策,商城暂停):工作台「商品与履约」写入口(建商品/改 SKU 与价格/时段容量/下架)的子开关,
     * 默认关闭。与 /api/config/features 下发给小程序的 merchantDeskProducts 同一 sys_config 键。
     */
    public static final String PRODUCTS_KEY = "feature.flag.merchantDeskProducts";

    @Autowired private ISysConfigService configService;

    public boolean isEnabled() {
        try {
            String value = configService.selectConfigByKey(CONFIG_KEY);
            return value != null && "true".equalsIgnoreCase(value.trim());
        } catch (Exception ignored) {
            return false;
        }
    }

    public boolean productsEnabled() {
        try {
            return ComplianceFeatureSwitches.flagOn(configService.selectConfigByKey(PRODUCTS_KEY));
        } catch (Exception ignored) {
            return false;
        }
    }

    public void requireProductsEnabled() {
        if (!productsEnabled()) {
            throw new MerchantDeskException(404, MerchantDeskException.DISABLED, "商品与履约功能暂未开放");
        }
    }

    public void requireEnabled() {
        if (!isEnabled()) {
            throw new MerchantDeskException(404, MerchantDeskException.DISABLED, "商家工作台新功能暂未开放");
        }
    }
}
