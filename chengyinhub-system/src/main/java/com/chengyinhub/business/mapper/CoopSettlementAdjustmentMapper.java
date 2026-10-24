package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CoopSettlementAdjustment;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;

public interface CoopSettlementAdjustmentMapper {
    java.util.List<com.chengyinhub.business.domain.CoopSettlementAdjustment> selectExecutedBySettlementForUpdate(
            @org.apache.ibatis.annotations.Param("settlementId") Long settlementId);
    int insertIgnoreAdjustment(CoopSettlementAdjustment adjustment);
    CoopSettlementAdjustment selectByClaimEventForUpdate(@Param("claimId") Long claimId,
            @Param("entryKind") String entryKind, @Param("eventKey") String eventKey);
    BigDecimal sumExecutedBySettlement(Long settlementId);
}
