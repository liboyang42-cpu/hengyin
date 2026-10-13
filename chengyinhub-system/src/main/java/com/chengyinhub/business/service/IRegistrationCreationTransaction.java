package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CmsRegistration;
import com.chengyinhub.business.domain.dto.RegistrationRequest;

import java.util.List;

/** requestId 建单唯一事务入口；调用方必须先完成 requestId 规范化与 replay 回读。 */
public interface IRegistrationCreationTransaction {
    List<CmsRegistration> create(Long memberId, RegistrationRequest request, String normalizedRequestId,
                                 String requestFingerprint);
}
