package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.EcomCombineOrder;
import com.chengyinhub.business.domain.EcomCombineRefund;
import com.chengyinhub.business.domain.EcomCombineReturn;
import com.chengyinhub.business.domain.EcomCombineSettlement;
import com.chengyinhub.business.domain.EcomCombineSubOrder;
import com.chengyinhub.business.domain.EcomSupplierPayable;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/** l-payout3 合单支付与供应商结算表。状态更新一律带期望旧状态（CAS），返回影响行数。 */
public interface EcomCombineMapper {

    // ---- 下单
    int insertCombineOrder(EcomCombineOrder row);
    EcomCombineOrder selectCombineOrder(@Param("outTradeNo") String outTradeNo);
    /** 首次支付成功时绑定合单号与卖家子单交易号；只写一次。 */
    int bindCombinePaid(@Param("outTradeNo") String outTradeNo, @Param("combineOutTradeNo") String combineOutTradeNo,
                        @Param("sellerTransactionId") String sellerTransactionId);
    int insertSubOrder(EcomCombineSubOrder row);
    List<EcomCombineSubOrder> selectSubOrders(@Param("combineOutTradeNo") String combineOutTradeNo);
    int bindSubOrderTransaction(@Param("combineOutTradeNo") String combineOutTradeNo, @Param("role") String role,
                                @Param("transactionId") String transactionId);

    // ---- 退款
    int insertRefund(EcomCombineRefund row);
    EcomCombineRefund selectRefund(@Param("outRefundNo") String outRefundNo);
    List<EcomCombineRefund> selectRefundsByParent(@Param("parentOutRefundNo") String parentOutRefundNo);
    List<EcomCombineRefund> selectRefundsByTrade(@Param("businessOutTradeNo") String businessOutTradeNo);
    int updateRefundState(@Param("outRefundNo") String outRefundNo, @Param("expectedState") String expectedState,
                          @Param("state") String state, @Param("refundId") String refundId, @Param("failReason") String failReason);

    // ---- 结算
    List<EcomCombineOrder> selectPaidCombineOrdersForTopic(@Param("topicId") Long topicId);
    List<Long> selectSettledTopicsMissingCombineSettlements(@Param("limit") int limit);
    int insertSettlement(EcomCombineSettlement row);
    EcomCombineSettlement selectSettlement(@Param("businessOutTradeNo") String businessOutTradeNo, @Param("topicId") Long topicId);
    EcomCombineSettlement selectSettlementById(@Param("id") Long id);
    List<EcomCombineSettlement> selectSettlementsByTrade(@Param("businessOutTradeNo") String businessOutTradeNo);
    List<EcomCombineSettlement> selectDuePlannedSettlements(@Param("now") Date now, @Param("limit") int limit);
    List<EcomCombineSettlement> selectSettlementsByState(@Param("state") String state, @Param("limit") int limit);
    int updateSettlementState(@Param("id") Long id, @Param("expectedState") String expectedState, @Param("state") String state,
                              @Param("wxOrderId") String wxOrderId, @Param("manualReason") String manualReason);

    int insertReturn(EcomCombineReturn row);
    EcomCombineReturn selectReturn(@Param("outReturnNo") String outReturnNo);
    List<EcomCombineReturn> selectReturnsBySettlement(@Param("combineSettlementId") Long combineSettlementId);
    int updateReturnState(@Param("outReturnNo") String outReturnNo, @Param("expectedState") String expectedState,
                          @Param("state") String state, @Param("wxReturnNo") String wxReturnNo, @Param("failReason") String failReason);

    // ---- 供应商结算
    int insertPayable(EcomSupplierPayable row);
    List<EcomSupplierPayable> selectPayablesByTrade(@Param("businessOutTradeNo") String businessOutTradeNo);
    List<EcomSupplierPayable> selectPayablesForAdmin(@Param("state") String state, @Param("limit") int limit);
    int markPayablePaid(@Param("id") Long id, @Param("paidReference") String paidReference, @Param("paidBy") String paidBy);
    int holdPayablesForRefund(@Param("businessOutTradeNo") String businessOutTradeNo);

    // ---- T+14 / 结算后退款接点
    /** 结算头里由合单收款的分项（原始份额 + 原始退款调整），元。 */
    BigDecimal sumCombinedPortionForSettlement(@Param("settlementId") Long settlementId);
    int countCombinedPaymentFact(@Param("paymentFactId") Long paymentFactId);
}
