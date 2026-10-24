package com.chengyinhub.business.service;

import java.util.Map;

public interface IMerchantCommerceService {
    boolean isSelfCheckoutEnabled();

    Map<String, String> createOrder(Long memberId, Long merchantId, String openid, String bizType, String requestId);

    Map<String, String> orderStatus(Long memberId, Long merchantId, String orderSn);

    boolean supportsOutTradeNo(String outTradeNo);

    boolean handlePaidOrder(String outTradeNo, boolean paid, Integer paidAmountFen, String transactionId,
            com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference reference);

    /** 用微信真实查单补记漏掉的回调；不改变未确认状态。 */
    void reconcileOrder(String outTradeNo);
}
