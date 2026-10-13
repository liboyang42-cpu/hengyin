package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.dto.DispatchResult;

/** 商家报名择优调配的唯一业务入口。 */
public interface IDispatchService {

    DispatchResult confirmRegistration(Long registrationId);

    DispatchResult rejectRegistration(Long registrationId, String reason);

    DispatchResult confirmNodeMerchant(Long topicId, Long nodeId, Long merchantMemberId);
}
