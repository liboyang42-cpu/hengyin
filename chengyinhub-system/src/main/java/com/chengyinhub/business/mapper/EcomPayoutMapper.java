package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CreatorPayoutRequest;
import com.chengyinhub.business.domain.EcomLedgerComponent;
import com.chengyinhub.business.domain.EcomOrderSeller;
import com.chengyinhub.business.domain.EcomProfitShareLine;
import com.chengyinhub.business.domain.EcomProfitShareOrder;
import com.chengyinhub.business.domain.EcomProfitShareReturn;
import com.chengyinhub.business.domain.EcomRefund;
import com.chengyinhub.business.domain.EcomSubMerchant;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** l-payout 电商收付通与创作者打款表。状态更新一律带期望旧状态（CAS），返回影响行数。 */
public interface EcomPayoutMapper {

    EcomSubMerchant selectSubMerchant(@Param("ownerType") String ownerType, @Param("ownerId") Long ownerId);
    EcomSubMerchant selectSubMerchantById(@Param("id") Long id);
    List<EcomSubMerchant> selectSubMerchantsInProgress(@Param("limit") int limit);
    int insertSubMerchant(EcomSubMerchant row);
    int markApplymentSubmitted(@Param("id") Long id, @Param("applymentId") Long applymentId);
    int updateApplymentStatus(@Param("id") Long id, @Param("expectedState") String expectedState,
                              @Param("state") String state, @Param("stateDesc") String stateDesc,
                              @Param("signUrl") String signUrl, @Param("subMchid") String subMchid,
                              @Param("applymentId") Long applymentId);
    int touchApplymentQueried(@Param("id") Long id, @Param("stateDesc") String stateDesc, @Param("signUrl") String signUrl);
    /** 会员作为商家主账号或俱乐部主理人时，其已完成进件（FINISH）的二级商户号；多于一个时返回全部，由调用方拒绝歧义。 */
    List<String> selectFinishedSubMchidsForMember(@Param("memberId") Long memberId);
    Long selectSellerMemberId(@Param("ownerType") String ownerType, @Param("ownerId") Long ownerId);

    int insertOrderSeller(EcomOrderSeller row);
    EcomOrderSeller selectOrderSeller(@Param("outTradeNo") String outTradeNo);
    int bindOrderSellerTransaction(@Param("outTradeNo") String outTradeNo, @Param("transactionId") String transactionId);
    /** 主题下已支付（有 transaction_id）、以二级商户为卖家的报名单。 */
    List<EcomOrderSeller> selectPaidSubMerchantTradesForTopic(@Param("topicId") Long topicId);
    List<EcomLedgerComponent> selectLedgerComponents(@Param("topicId") Long topicId, @Param("payerRegistrationId") Long payerRegistrationId);

    int insertShareOrder(EcomProfitShareOrder row);
    EcomProfitShareOrder selectShareOrder(@Param("outTradeNo") String outTradeNo, @Param("topicId") Long topicId);
    List<EcomProfitShareOrder> selectShareOrdersByTrade(@Param("outTradeNo") String outTradeNo);
    List<EcomProfitShareOrder> selectShareOrdersByState(@Param("state") String state, @Param("limit") int limit);
    int updateShareOrderState(@Param("id") Long id, @Param("expectedState") String expectedState, @Param("state") String state,
                              @Param("wxOrderId") String wxOrderId, @Param("manualReason") String manualReason);
    int insertShareLine(EcomProfitShareLine row);
    List<EcomProfitShareLine> selectShareLines(@Param("shareOrderId") Long shareOrderId);

    int insertReturn(EcomProfitShareReturn row);
    EcomProfitShareReturn selectReturn(@Param("outReturnNo") String outReturnNo);
    List<EcomProfitShareReturn> selectReturnsByShareOrder(@Param("shareOrderId") Long shareOrderId);
    int updateReturnState(@Param("id") Long id, @Param("expectedState") String expectedState, @Param("state") String state,
                          @Param("wxReturnNo") String wxReturnNo, @Param("failReason") String failReason);

    int insertCreatorPayout(CreatorPayoutRequest row);
    CreatorPayoutRequest selectCreatorPayout(@Param("sourceType") String sourceType, @Param("sourceId") Long sourceId,
                                             @Param("creatorMemberId") Long creatorMemberId);
    int updateCreatorPayoutState(@Param("id") Long id, @Param("expectedState") String expectedState, @Param("state") String state,
                                 @Param("providerRef") String providerRef, @Param("failReason") String failReason);

    // ---- l-payout2：接入现有支付链路 ----

    int insertRefund(EcomRefund row);
    EcomRefund selectRefund(@Param("outRefundNo") String outRefundNo);
    List<EcomRefund> selectRefundsByTrade(@Param("outTradeNo") String outTradeNo);
    List<EcomRefund> selectRefundsByStates(@Param("states") List<String> states, @Param("limit") int limit);
    int updateRefundState(@Param("outRefundNo") String outRefundNo, @Param("expectedState") String expectedState,
                          @Param("state") String state, @Param("refundId") String refundId, @Param("failReason") String failReason);

    /** 计划中、且其全部结算行都已到期（payable_time ≤ now、未删除）、主题无未结客诉的分账单：与 T+14 入余额同一时点。 */
    List<EcomProfitShareOrder> selectDuePlannedShareOrders(@Param("now") java.util.Date now, @Param("limit") int limit);
    /** 已结算（is_settlement=1）、有账本份额、却还没有分账计划的二级商户已付单所在主题。 */
    List<Long> selectSettledTopicsMissingSharePlans(@Param("limit") int limit);
    /**
     * 一张结算头里来自「以二级商户为卖家、已由微信收款」的那部分净额（原始份额 + 原始退款调整），单位元。
     * 这部分钱在微信侧（二级商户冻结资金 / 分账），T+14 不能再记进会员余额。
     */
    java.math.BigDecimal sumPartnerPortionForSettlement(@Param("settlementId") Long settlementId);
    /** 该支付事实是否是以二级商户为卖家的支付（事实商户号 = 登记的 sub_mchid）。 */
    int countPartnerPaymentFact(@Param("paymentFactId") Long paymentFactId);

    List<EcomSubMerchant> selectSubMerchantsForAdmin(@Param("state") String state, @Param("limit") int limit);
    List<EcomProfitShareOrder> selectShareOrdersForAdmin(@Param("state") String state, @Param("topicId") Long topicId,
                                                         @Param("limit") int limit);
    List<EcomProfitShareReturn> selectReturnsByState(@Param("state") String state, @Param("limit") int limit);
    EcomProfitShareOrder selectShareOrderById(@Param("id") Long id);
    EcomProfitShareReturn selectReturnById(@Param("id") Long id);
    int deleteShareLines(@Param("shareOrderId") Long shareOrderId);
    /** 人工单重新计划：仅 MANUAL 且从未提交过微信（wx_order_id 为空）的单可以改写计划。 */
    int replanShareOrder(@Param("id") Long id, @Param("state") String state, @Param("sharedFen") long sharedFen,
                         @Param("planHash") String planHash, @Param("manualReason") String manualReason);

    // ---- D-05/D-09/D-16 俱乐部个人分账（新增，不改既有语句） ----
    com.chengyinhub.business.domain.ClubPayoutBeneficiary selectFrozenClubBeneficiary(@Param("scopeType") String scopeType, @Param("scopeId") Long scopeId);

    com.chengyinhub.business.domain.ClubPayoutBeneficiary selectFrozenClubBeneficiaryForUpdate(@Param("scopeType") String scopeType, @Param("scopeId") Long scopeId);

    int markClubBeneficiaryReady(@Param("scopeType") String scopeType, @Param("scopeId") Long scopeId);

    int insertFrozenClubBeneficiary(com.chengyinhub.business.domain.ClubPayoutBeneficiary beneficiary);

    com.chengyinhub.business.domain.EcomPersonalReceiver selectByMemberAndAppid(@Param("memberId") Long memberId, @Param("appid") String appid);

    com.chengyinhub.business.domain.EcomPersonalReceiver selectByMemberAndAppidForUpdate(@Param("memberId") Long memberId, @Param("appid") String appid);

    /** Insert only: no update/upsert can replace a verified identity. */
    int insertVerified(com.chengyinhub.business.domain.EcomPersonalReceiver receiver);

    /** Session obligations use negative activity/detail ids in the shared payout scope namespace. */
    List<Long> selectMissingActivityIds(@Param("limit") int limit);

    List<EcomOrderSeller> selectPaidTrades(@Param("activityId") Long activityId);

    List<EcomLedgerComponent> selectComponents(@Param("activityId") Long activityId,
                                               @Param("registrationId") Long registrationId);
}
