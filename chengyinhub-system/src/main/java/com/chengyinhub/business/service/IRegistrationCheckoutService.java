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

    /**
     * 玩家取消未支付报名。待支付(payment_status=0)照旧走 {@link ICmsRegistrationService#cancelRegistration};
     * 已发起预下单(payment_status=1,关掉微信支付面板后就停在这里)先按原支付号向微信查单:
     * 未支付 ⇒ 先关微信单再本地关单、回补库存/积分/权益;正在支付或已支付 ⇒ 拒绝取消;查不清 ⇒ 拒绝并保持原状。
     *
     * @return true=已取消(含此前已取消)
     */
    boolean cancelUnpaid(Long memberId, Long registrationId, String userName);
}
