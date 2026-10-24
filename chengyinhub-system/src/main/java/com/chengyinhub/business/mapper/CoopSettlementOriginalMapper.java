package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.*;
import com.chengyinhub.business.domain.dto.CoopOriginalRefundScope;
import com.chengyinhub.business.domain.dto.WechatVerifiedPaymentFact;
import org.apache.ibatis.annotations.Param;
import java.util.List;
import java.math.BigDecimal;

/** 原close内部的逐订单分项与成功冲回；所有写调用必须加入原topic资金事务。 */
public interface CoopSettlementOriginalMapper {
    List<CmsRegistration> selectOriginalRegistrationsForUpdate(@Param("topicId") Long topicId);
    WechatVerifiedPaymentFact.Reference selectPaymentVerifiedReferenceForUpdate(@Param("registrationId") Long registrationId);
    List<CoopSettlementDetail> selectOriginalDetailsForHeadForUpdate(@Param("settlementId") Long settlementId);
    int insertComponent(CoopSettlementOriginalComponent component);
    List<CoopSettlementOriginalComponent> selectComponentsForPayerForUpdate(
            @Param("cashCloseId") Long cashCloseId, @Param("payerRegistrationId") Long payerRegistrationId);
    List<CoopSettlementOriginalComponent> selectComponentsForCloseForUpdate(@Param("cashCloseId") Long cashCloseId);
    List<CoopSettlement> selectTopicHeadsForUpdate(@Param("topicId") Long topicId);
    List<BigDecimal> selectHeadCreditAmountsForUpdate(@Param("settlementId") Long settlementId,
            @Param("memberId") Long memberId, @Param("topicId") Long topicId);
    int bindHeadToClose(@Param("settlementId") Long settlementId, @Param("cashCloseId") Long cashCloseId,
            @Param("topicId") Long topicId, @Param("receiverKind") String receiverKind);
    RefundApplication selectRefundForUpdate(@Param("refundId") Long refundId);
    CoopOriginalRefundScope selectRefundScope(@Param("refundId") Long refundId);
    CoopOriginalRefundScope selectRefundScopeForUpdate(@Param("refundId") Long refundId);
    WechatVerifiedPaymentFact.Reference selectRefundVerifiedReferenceForUpdate(@Param("refundId") Long refundId);
    CoopSettlementRefundReceipt selectReceiptForUpdate(@Param("refundId") Long refundId);
    List<CoopSettlementRefundReceipt> selectReceiptsForPayerForUpdate(
            @Param("cashCloseId") Long cashCloseId, @Param("payerRegistrationId") Long payerRegistrationId);
    int insertReceipt(CoopSettlementRefundReceipt receipt);
    int insertAdjustment(CoopSettlementOriginalAdjustment adjustment);
    List<CoopSettlementOriginalAdjustment> selectAdjustmentsForReceiptForUpdate(@Param("receiptId") Long receiptId);
    List<CoopSettlementOriginalAdjustment> selectAdjustmentsForSettlementForUpdate(@Param("settlementId") Long settlementId);
}
