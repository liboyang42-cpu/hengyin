package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.SettlementClaim;

public interface SettlementClaimMapper {
    int insertClaim(SettlementClaim claim);
    SettlementClaim selectByEconomicKeyForUpdate(SettlementClaim key);
}
