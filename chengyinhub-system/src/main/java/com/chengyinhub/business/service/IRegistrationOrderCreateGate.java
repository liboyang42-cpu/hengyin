package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.RegistrationRequest;

/** 仅在持久化幂等 winner 不存在时执行的建单前报价与支付就绪门禁。 */
public interface IRegistrationOrderCreateGate {
    void validate(Long memberId, RegistrationRequest request);

    /** 购买前核对已保存的参与人姓名与账号绑定手机号；不使用客户端资料作为完成证明。 */
    void validatePurchaseProfile(Long memberId, String realName);

    /**
     * 只看「谁买、买的是谁家」的拒绝(商家打烊、商家报俱乐部活动),与 validate 同一套判据和文案。
     * 报价也走这一道,免得给一个下单时必被拒的人报出价格(r1 F3)。
     */
    void validateBuyer(Long memberId, RegistrationRequest request);
}
