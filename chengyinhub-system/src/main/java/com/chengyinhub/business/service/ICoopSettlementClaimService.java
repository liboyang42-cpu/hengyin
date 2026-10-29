package com.chengyinhub.business.service;

import com.chengyinhub.business.domain.CoopOrder;
import com.chengyinhub.business.domain.CoopSettlement;
import com.chengyinhub.business.domain.dto.CoopSettlementPlan;

import java.util.Date;

public interface ICoopSettlementClaimService {
    /** 外层无业务事务：保留已关账2/1原SUCCESS待处理身份；false不代表支付未执行。 */
    boolean retainClosedPaidHalfStateSuccess(Long registrationId, com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference reference);
    /** 原topic资金事务内、支付修复/CAS之前重验；不得以外层观察代替此栅栏。 */
    void requirePaidHalfStateCashAdmission(Long registrationId, com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact.Reference reference);

    /** 加入A终态事务；仅原渠道SUCCESS/既有人工双审完成后冲回原冻结分项。 */
    void reverseCompletedRegistrationRefund(Long refundId);

    /** 持原topic锁区分新原分项owner；原close缺分项是冲突，不能降级为旧追收。 */
    boolean hasFrozenOriginalRefundComponents(Long refundId);
    /** Same original transaction; empty means no applicable confirmed V1 contract, never a receipt. */
    java.util.Optional<Long> recordConfirmedStationFulfillment(Long verificationAuditEventId);
    CoopSettlementPlan prepare(CoopOrder order, Date settlementSnapshotAt);
    void persist(CoopSettlementPlan plan, CoopSettlement head);
    void appendExecutedAdjustment(Long claimId, String eventKey, java.math.BigDecimal signedAmount);
}
