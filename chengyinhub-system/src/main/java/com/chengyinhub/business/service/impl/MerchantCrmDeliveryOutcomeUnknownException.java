package com.chengyinhub.business.service.impl;

/** 外部渠道已开始执行但没有可证明回执；为保证最多一次，后续自动流程不得重发。 */
public class MerchantCrmDeliveryOutcomeUnknownException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public MerchantCrmDeliveryOutcomeUnknownException(Throwable cause) {
        super("投递结果未知，已阻止自动重发", cause);
    }

    public MerchantCrmDeliveryOutcomeUnknownException() {
        super("投递结果未知，已阻止自动重发");
    }
}
