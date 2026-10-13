package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.vo.MerchantOpsVO;

public interface IMerchantDetailAggregationService {
    MerchantOpsVO.Detail getPublicDetail(Long merchantId);
}
