package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.vo.MerchantOpsVO;

public interface IMerchantPrerequisiteService {
    MerchantOpsVO.PrerequisiteChecklist calculate(Long principalMemberId);
}
