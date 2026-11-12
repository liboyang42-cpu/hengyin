package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.ClubPayoutBeneficiary;
import org.apache.ibatis.annotations.Param;

public interface ClubPayoutBeneficiaryMapper {
    ClubPayoutBeneficiary selectFrozen(@Param("scopeType") String scopeType, @Param("scopeId") Long scopeId);
    ClubPayoutBeneficiary selectFrozenForUpdate(@Param("scopeType") String scopeType, @Param("scopeId") Long scopeId);
    int markReady(@Param("scopeType") String scopeType, @Param("scopeId") Long scopeId);
    int insertFrozen(ClubPayoutBeneficiary beneficiary);
}
