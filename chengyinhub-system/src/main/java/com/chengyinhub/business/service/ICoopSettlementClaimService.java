package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.CoopSettlement;
import com.chengyinhub.business.domain.dto.CoopSettlementPlan;

import java.util.Date;

public interface ICoopSettlementClaimService {
    CoopSettlementPlan prepare(CoopOrder order, Date settlementSnapshotAt);
    void persist(CoopSettlementPlan plan, CoopSettlement head);
    void appendExecutedAdjustment(Long claimId, String eventKey, java.math.BigDecimal signedAmount);
}
