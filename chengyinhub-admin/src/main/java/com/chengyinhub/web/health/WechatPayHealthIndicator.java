package com.chengyinhub.web.health;

import com.chengyinhub.business.service.IWechatPayService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/** 微信支付初始化状态，不输出商户配置或异常详情。 */
@Component("wechatPay")
public class WechatPayHealthIndicator implements HealthIndicator
{
    private final IWechatPayService wechatPayService;

    public WechatPayHealthIndicator(IWechatPayService wechatPayService)
    {
        this.wechatPayService = wechatPayService;
    }

    @Override
    public Health health()
    {
        return wechatPayService.isReady()
                ? Health.up().withDetail("state", "ready").build()
                : Health.down().withDetail("state", "unavailable").build();
    }
}
