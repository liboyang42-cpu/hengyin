package com.chengyinhub.business.service.support;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 现金通道是否已接到本域订单。
 *
 * <p>默认关闭：{@code JdbcWechatPaymentOrderOwner} 与
 * {@code WechatPaymentCallbackRouter} 尚未识别 MKO 订单前缀，贸然预下单会得到
 * "回调无人认领"的悬空支付。打开开关前必须先由原 owner 合入路由补丁并完成金额锁定，
 * 否则报价只能显示积分通道，现金单只落 PENDING_CASH。
 */
@Component
public class MarketingCashChannel {

    private final boolean enabled;

    public MarketingCashChannel(@Value("${marketing.product.cash-enabled:false}") boolean enabled) {
        this.enabled = enabled;
    }

    public boolean enabled() { return enabled; }

    public String unavailableReason() {
        return enabled ? null : "PAYMENT_OWNER_ROUTE_NOT_WIRED";
    }
}
