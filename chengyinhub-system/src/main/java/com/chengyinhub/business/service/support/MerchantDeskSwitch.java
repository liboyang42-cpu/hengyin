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

    /**
     * Q1 merchant-reach(owner 2026-11-15):商家非资金写入的唯一开关,与 /api/config/features 下发给 App 的
     * merchantNonMoney 同一 sys_config 键。打开时工作台(今日到店/核销台/对账只读)随之打开;
     * 商品与价格写入仍只认 {@link #PRODUCTS_KEY},资金写入仍只认资金开关。
     */
    public static final String NON_MONEY_KEY = "feature.flag.merchantNonMoney";

    @Autowired private ISysConfigService configService;

    public boolean isEnabled() {
        try {
            String value = configService.selectConfigByKey(CONFIG_KEY);
            if (value != null && "true".equalsIgnoreCase(value.trim())) return true;
        } catch (Exception ignored) {
            // fall through to the non-money switch
        }
        return nonMoneyEnabled();
    }

    public boolean nonMoneyEnabled() {
        try {
            return ComplianceFeatureSwitches.flagOn(configService.selectConfigByKey(NON_MONEY_KEY));
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
