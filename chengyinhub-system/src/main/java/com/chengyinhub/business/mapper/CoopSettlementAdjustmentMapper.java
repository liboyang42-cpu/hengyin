package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CoopSettlementAdjustment;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;

public interface CoopSettlementAdjustmentMapper {
    int insertIgnoreAdjustment(CoopSettlementAdjustment adjustment);
    CoopSettlementAdjustment selectByClaimEventForUpdate(@Param("claimId") Long claimId,
            @Param("entryKind") String entryKind, @Param("eventKey") String eventKey);
    BigDecimal sumExecutedBySettlement(Long settlementId);
}
