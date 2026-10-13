package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.RegistrationQuoteResult;
import com.chengyinhub.business.domain.dto.RegistrationRequest;

/** 报名报价、建单与预下单的窄应用边界。 */
public interface IRegistrationCheckoutService {
    RegistrationQuoteResult quote(Long memberId, RegistrationRequest request);

    boolean isPaymentReady();

    RegistrationCheckoutResult createOrReplay(Long memberId, RegistrationRequest request);

    RegistrationCheckoutResult retryPayment(Long memberId, Long registrationId);

    /**
     * 指定支付渠道重新发起支付。appChannel=true 走 trade_type=APP。
     * 单参重载等价于 appChannel=false(小程序 JSAPI),保持既有调用方零改动。
     */
    RegistrationCheckoutResult retryPayment(Long memberId, Long registrationId, boolean appChannel);
}
