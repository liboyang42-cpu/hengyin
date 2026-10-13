package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CoopSettlementDetail;

public interface CoopSettlementDetailMapper {
    int insertIgnoreDetail(CoopSettlementDetail detail);
    CoopSettlementDetail selectByClaimIdForUpdate(Long claimId);
    int refreshOriginAggregate(Long settlementId);
}
