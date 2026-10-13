package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.RegistrationOrderResult;
import com.chengyinhub.business.domain.dto.RegistrationRequest;

/** requestId 唯一建单与冲突回读边界。 */
public interface IRegistrationOrderService {
    /** 先查持久化 winner，再进入唯一建单。 */
    RegistrationOrderResult createOrReplay(Long memberId, RegistrationRequest request);
}
