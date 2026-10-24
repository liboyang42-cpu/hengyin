package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.CoopSettlement;
import com.chengyinhub.business.domain.dto.CoopSettlementPlan;

import java.util.Date;

public interface ICoopSettlementClaimService {
    /** 加入A终态事务；仅原渠道SUCCESS/既有人工双审完成后冲回原冻结分项。 */
    void reverseCompletedRegistrationRefund(Long refundId);

    /** 持原topic锁区分新原分项owner；原close缺分项是冲突，不能降级为旧追收。 */
    boolean hasFrozenOriginalRefundComponents(Long refundId);
    CoopSettlementPlan prepare(CoopOrder order, Date settlementSnapshotAt);
    void persist(CoopSettlementPlan plan, CoopSettlement head);
    void appendExecutedAdjustment(Long claimId, String eventKey, java.math.BigDecimal signedAmount);
}
