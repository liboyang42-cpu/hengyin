package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.CoopSettlement;
import com.chengyinhub.business.domain.dto.CoopSettlementPlan;

import java.util.Date;

public interface ICoopSettlementClaimService {
    /** 原head/detail持久化后，同事务冻结可证的一对一原件；返回明确FROZEN或NOT_ENABLED原因。 */
    String freezeSingleOrderOriginals(com.chengyinhub.business.domain.CmsTopic topic,
            com.chengyinhub.business.domain.dto.TopicSettlementCashBasis basis, Date frozenAt);
    /** 加入A终态事务；仅原渠道SUCCESS/既有人工双审完成后冲回原冻结分项。 */
    void reverseCompletedRegistrationRefund(Long refundId);

    /** 持原topic锁区分新原分项owner；原close缺分项是冲突，不能降级为旧追收。 */
    boolean hasFrozenOriginalRefundComponents(Long refundId);
    CoopSettlementPlan prepare(CoopOrder order, Date settlementSnapshotAt);
    void persist(CoopSettlementPlan plan, CoopSettlement head);
    void appendExecutedAdjustment(Long claimId, String eventKey, java.math.BigDecimal signedAmount);
}
