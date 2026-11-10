package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.CreatorPayoutRequest;
import com.chengyinhub.business.domain.EcomLedgerComponent;
import com.chengyinhub.business.domain.EcomOrderSeller;
import com.chengyinhub.business.domain.EcomProfitShareLine;
import com.chengyinhub.business.domain.EcomProfitShareOrder;
import com.chengyinhub.business.domain.EcomProfitShareReturn;
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
}
