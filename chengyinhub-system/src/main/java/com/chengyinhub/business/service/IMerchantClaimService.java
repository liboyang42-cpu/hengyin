package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.MerchantClaim;
import com.chengyinhub.business.domain.dto.MerchantClaimDTO;

import java.util.List;

public interface IMerchantClaimService {
    MerchantClaim submit(Long claimantMemberId, MerchantClaimDTO.SubmitRequest request);
    List<MerchantClaim> status(Long claimantMemberId);
    List<MerchantClaim> pending(int limit);
    MerchantClaim get(Long claimId);
    MerchantClaim audit(Long auditorId, MerchantClaimDTO.AuditRequest request);
}
