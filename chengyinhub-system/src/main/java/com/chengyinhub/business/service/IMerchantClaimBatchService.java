package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.MerchantClaimBatchResult;
import com.chengyinhub.business.domain.dto.MerchantClaimDTO;

import java.util.List;

public interface IMerchantClaimBatchService {
    List<MerchantClaimBatchResult> auditBatch(Long auditorId, MerchantClaimDTO.BatchRequest request);
}
