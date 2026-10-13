package com.chengyinhub.business.mapper;

import com.chengyinhub.business.domain.MerchantCrmCampaign;
import com.chengyinhub.business.domain.MerchantCrmCampaignRecipient;
import com.chengyinhub.business.domain.MerchantCrmDeliveryGuard;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface MerchantCrmCampaignMapper {
    int insertCampaign(MerchantCrmCampaign campaign);
    int insertRecipient(MerchantCrmCampaignRecipient recipient);
    int insertDeliveryGuard(MerchantCrmDeliveryGuard guard);
    MerchantCrmCampaign selectByRequestId(@Param("merchantId") Long merchantId,
                                           @Param("requestId") String requestId);
    MerchantCrmCampaign selectOwnedCampaign(@Param("merchantId") Long merchantId,
                                             @Param("campaignId") Long campaignId);
    List<MerchantCrmCampaign> selectOwnedCampaigns(@Param("merchantId") Long merchantId,
                                                   @Param("limit") Integer limit);
    List<MerchantCrmCampaignRecipient> selectOwnedRecipients(@Param("merchantId") Long merchantId,
                                                              @Param("campaignId") Long campaignId,
                                                              @Param("limit") Integer limit);
    List<Long> selectPendingRecipientIds(@Param("merchantId") Long merchantId,
                                         @Param("campaignId") Long campaignId,
                                         @Param("limit") Integer limit);
    MerchantCrmCampaignRecipient selectRecipientForUpdate(@Param("merchantId") Long merchantId,
                                                           @Param("campaignId") Long campaignId,
                                                           @Param("recipientId") Long recipientId);
    MerchantCrmDeliveryGuard selectDeliveryGuardForUpdate(@Param("merchantId") Long merchantId,
                                                           @Param("memberId") Long memberId,
                                                           @Param("channel") String channel);
    MerchantCrmDeliveryGuard selectDeliveryGuardByRecipientForUpdate(@Param("merchantId") Long merchantId,
                                                                      @Param("campaignId") Long campaignId,
                                                                      @Param("recipientId") Long recipientId);
    int countDeliveredToday(@Param("merchantId") Long merchantId,
                            @Param("memberId") Long memberId,
                            @Param("channel") String channel,
                            @Param("campaignId") Long campaignId);
    int markCampaignProcessing(@Param("merchantId") Long merchantId,
                               @Param("campaignId") Long campaignId,
                               @Param("expectedStatus") String expectedStatus,
                               @Param("processingToken") String processingToken,
                               @Param("leaseSeconds") Integer leaseSeconds);
    List<MerchantCrmCampaign> selectStaleProcessingCampaigns(@Param("limit") Integer limit);
    int reclaimStaleCampaign(@Param("merchantId") Long merchantId,
                             @Param("campaignId") Long campaignId,
                             @Param("ownerMemberId") Long ownerMemberId,
                             @Param("expectedVersion") Long expectedVersion,
                             @Param("processingToken") String processingToken,
                             @Param("leaseSeconds") Integer leaseSeconds);
    int invalidateStaleCampaign(@Param("merchantId") Long merchantId,
                                @Param("campaignId") Long campaignId,
                                @Param("ownerMemberId") Long ownerMemberId,
                                @Param("expectedVersion") Long expectedVersion);
    int invalidateClaimedCampaign(@Param("merchantId") Long merchantId,
                                  @Param("campaignId") Long campaignId,
                                  @Param("ownerMemberId") Long ownerMemberId,
                                  @Param("processingToken") String processingToken);
    int renewCampaignLease(@Param("merchantId") Long merchantId,
                           @Param("campaignId") Long campaignId,
                           @Param("processingToken") String processingToken,
                           @Param("leaseSeconds") Integer leaseSeconds);
    int resetRetryableRecipients(@Param("merchantId") Long merchantId,
                                 @Param("campaignId") Long campaignId);
    int markRecipientSkipped(@Param("merchantId") Long merchantId,
                             @Param("campaignId") Long campaignId,
                             @Param("recipientId") Long recipientId,
                             @Param("status") String status,
                             @Param("failureCode") String failureCode,
                             @Param("failureMessage") String failureMessage);
    int markRecipientDelivered(@Param("merchantId") Long merchantId,
                               @Param("campaignId") Long campaignId,
                               @Param("recipientId") Long recipientId,
                               @Param("messageReceiptId") Long messageReceiptId,
                               @Param("couponHistoryId") Long couponHistoryId);
    int markRecipientFailed(@Param("merchantId") Long merchantId,
                            @Param("campaignId") Long campaignId,
                            @Param("recipientId") Long recipientId,
                            @Param("failureCode") String failureCode,
                            @Param("failureMessage") String failureMessage);
    int markRecipientRetryableBusy(@Param("merchantId") Long merchantId,
                                   @Param("campaignId") Long campaignId,
                                   @Param("recipientId") Long recipientId,
                                   @Param("failureMessage") String failureMessage);
    int reclaimReservedDeliveryGuard(@Param("guardId") Long guardId,
                                     @Param("expectedVersion") Long expectedVersion,
                                     @Param("leaseToken") String leaseToken,
                                     @Param("leaseSeconds") Integer leaseSeconds);
    int markDeliveryGuardDispatching(@Param("guardId") Long guardId,
                                     @Param("leaseToken") String leaseToken,
                                     @Param("expectedVersion") Long expectedVersion,
                                     @Param("leaseSeconds") Integer leaseSeconds);
    int markDeliveryGuardDelivered(@Param("guardId") Long guardId,
                                   @Param("leaseToken") String leaseToken,
                                   @Param("expectedVersion") Long expectedVersion,
                                   @Param("messageReceiptId") Long messageReceiptId,
                                   @Param("couponHistoryId") Long couponHistoryId);
    int markDeliveryGuardOutcomeUnknown(@Param("guardId") Long guardId,
                                        @Param("leaseToken") String leaseToken,
                                        @Param("expectedVersion") Long expectedVersion,
                                        @Param("failureCode") String failureCode);
    int markStaleDeliveryGuardOutcomeUnknown(@Param("guardId") Long guardId,
                                             @Param("expectedVersion") Long expectedVersion,
                                             @Param("failureCode") String failureCode);
    int finalizeCampaign(@Param("merchantId") Long merchantId,
                         @Param("campaignId") Long campaignId,
                         @Param("processingToken") String processingToken);
}
