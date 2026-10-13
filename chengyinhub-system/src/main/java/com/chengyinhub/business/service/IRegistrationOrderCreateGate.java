package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.RegistrationRequest;

/** 仅在持久化幂等 winner 不存在时执行的建单前报价与支付就绪门禁。 */
public interface IRegistrationOrderCreateGate {
    void validate(Long memberId, RegistrationRequest request);
}
