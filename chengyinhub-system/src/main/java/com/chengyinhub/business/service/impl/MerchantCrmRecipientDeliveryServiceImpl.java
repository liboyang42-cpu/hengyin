package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.BusinessAuditEvent;
import com.chengyinhub.business.domain.MerchantCrmCampaign;
import com.chengyinhub.business.domain.MerchantCrmCampaignRecipient;
import com.chengyinhub.business.domain.MerchantCrmMarketingConsent;
import com.chengyinhub.business.domain.MmsMerchant;
import com.chengyinhub.business.mapper.BusinessAuditEventMapper;
import com.chengyinhub.business.mapper.MerchantCrmCampaignMapper;
import com.chengyinhub.business.mapper.MerchantCrmMarketingConsentMapper;
import com.chengyinhub.business.mapper.MmsMerchantMapper;
import com.chengyinhub.business.service.IMerchantCrmRecipientDeliveryService;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.Objects;

@Service
public class MerchantCrmRecipientDeliveryServiceImpl implements IMerchantCrmRecipientDeliveryService {
    @Autowired private MerchantCrmCampaignMapper campaignMapper;
    @Autowired private MmsMerchantMapper merchantMapper;
    @Autowired private MerchantCrmMarketingConsentMapper consentMapper;
    @Autowired private MerchantCrmNotificationAdapter notificationAdapter;
    @Autowired private MerchantCrmCouponGrantAdapter couponGrantAdapter;
    @Autowired private MerchantCrmDeliveryGuardService deliveryGuardService;
    @Autowired private BusinessAuditEventMapper auditMapper;

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void deliver(Long merchantId, Long campaignId, Long recipientId) {
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        // 与商家停用/转让共用行锁，并持有到本次外部投递结束，关闭续租后的最终 TOCTOU 窗口。
        MmsMerchant merchant = merchantMapper.selectMmsMerchantByIdForUpdate(merchantId);
        if (merchant == null
                || !Objects.equals(merchantId, merchant.getId())
                || !Long.valueOf(1L).equals(merchant.getStatus())
                || !Long.valueOf(1L).equals(merchant.getAccountStatus())
                || !Integer.valueOf(0).equals(merchant.getDelFlag())) {
            throw new ServiceException("商家主体已失效，触达已停止", 409);
        }
        MerchantCrmCampaignRecipient recipient = campaignMapper.selectRecipientForUpdate(
                merchantId, campaignId, recipientId);
        if (recipient == null) throw new ServiceException("触达收件人不存在或不属于当前商家", 404);
        MerchantCrmCampaign campaign = campaignMapper.selectOwnedCampaign(merchantId, campaignId);
        if (campaign == null) throw new ServiceException("触达任务不存在或不属于当前商家", 404);
        if (!Objects.equals(merchantId, recipient.getMerchantId())
                || !Objects.equals(campaignId, recipient.getCampaignId())
                || !Objects.equals(merchantId, campaign.getMerchantRowId())
                || !Objects.equals(campaignId, campaign.getId())
                || !Objects.equals(campaign.getOwnerMemberId(), merchant.getMemberId())) {
            throw new ServiceException("商家归属已变化，触达已停止", 409);
        }
        if (MerchantCrmCampaignRecipient.DELIVERED.equals(recipient.getStatus())) return;
        if (!MerchantCrmCampaignRecipient.PENDING.equals(recipient.getStatus())) return;

        MerchantCrmMarketingConsent consent = consentMapper.selectLatestOptIn(
                recipient.getMemberId(), campaign.getOwnerMemberId(), merchantId, recipient.getChannel());
        if (consent == null) {
            requireOne(campaignMapper.markRecipientSkipped(merchantId, campaignId, recipientId,
                    MerchantCrmCampaignRecipient.SKIPPED_NO_CONSENT, "NO_CONSENT", "客户未同意或已退订"));
            appendAudit(campaign, recipient, "CRM_CAMPAIGN_SKIPPED_NO_CONSENT", null, null);
            return;
        }
        MerchantCrmDeliveryGuardService.Claim claim = deliveryGuardService.claim(merchantId, campaignId,
                recipientId, recipient.getMemberId(), recipient.getChannel());
        if (claim.getOutcome() == MerchantCrmDeliveryGuardService.Outcome.DAILY_CAP) {
            requireOne(campaignMapper.markRecipientSkipped(merchantId, campaignId, recipientId,
                    MerchantCrmCampaignRecipient.SKIPPED_FREQUENCY, "DAILY_CAP",
                    "客户今日已收到或已占用该商家同渠道触达额度"));
            appendAudit(campaign, recipient, "CRM_CAMPAIGN_SKIPPED_FREQUENCY", null, null);
            return;
        }
        if (claim.getOutcome() == MerchantCrmDeliveryGuardService.Outcome.DELIVERED) {
            requireOne(campaignMapper.markRecipientDelivered(merchantId, campaignId, recipientId,
                    claim.getMessageReceiptId(), claim.getCouponHistoryId()));
            appendAudit(campaign, recipient, "CRM_CAMPAIGN_DELIVERY_REPAIRED",
                    claim.getMessageReceiptId(), claim.getCouponHistoryId());
            return;
        }
        if (claim.getOutcome() == MerchantCrmDeliveryGuardService.Outcome.UNCERTAIN) {
            throw new MerchantCrmDeliveryOutcomeUnknownException();
        }
        if (claim.getOutcome() == MerchantCrmDeliveryGuardService.Outcome.BUSY) {
            throw new MerchantCrmDeliveryBusyException();
        }

        MerchantCrmDeliveryGuardService.Claim dispatching = deliveryGuardService.beginDispatch(claim);
        Long messageReceipt = null;
        Long couponReceipt = null;
        try {
            if (MerchantCrmMarketingConsent.COUPON.equals(recipient.getChannel())) {
                couponReceipt = couponGrantAdapter.grant(campaign.getOwnerMemberId(), merchantId,
                        recipient.getMemberId(), campaign.getCouponId());
            } else {
                messageReceipt = notificationAdapter.send(recipient.getMemberId(), campaign);
            }
            deliveryGuardService.markDelivered(dispatching, messageReceipt, couponReceipt);
        } catch (MerchantCrmCouponGrantAdapter.GrantRejectedException e) {
            try {
                deliveryGuardService.releaseForRetry(dispatching);
            } catch (RuntimeException releaseError) {
                e.addSuppressed(releaseError);
                try {
                    deliveryGuardService.markOutcomeUnknown(dispatching, "DELIVERY_OUTCOME_UNKNOWN");
                } catch (RuntimeException guardError) {
                    e.addSuppressed(guardError);
                }
                throw new MerchantCrmDeliveryOutcomeUnknownException(e);
            }
            throw e;
        } catch (RuntimeException e) {
            try {
                deliveryGuardService.markOutcomeUnknown(dispatching, "DELIVERY_OUTCOME_UNKNOWN");
            } catch (RuntimeException guardError) {
                e.addSuppressed(guardError);
            }
            throw new MerchantCrmDeliveryOutcomeUnknownException(e);
        }
        requireOne(campaignMapper.markRecipientDelivered(merchantId, campaignId, recipientId,
                messageReceipt, couponReceipt));
        appendAudit(campaign, recipient, "CRM_CAMPAIGN_DELIVERED", messageReceipt, couponReceipt);
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void recordFailure(Long merchantId, Long campaignId, Long recipientId, RuntimeException cause) {
        if (cause instanceof com.chengyinhub.common.config.RestoreDispatchGuard.Blocked) return;
        com.chengyinhub.common.config.RestoreDispatchGuard.requireDispatchAllowed();
        MerchantCrmCampaignRecipient recipient = campaignMapper.selectRecipientForUpdate(
                merchantId, campaignId, recipientId);
        if (recipient == null || !MerchantCrmCampaignRecipient.PENDING.equals(recipient.getStatus())) return;
        if (cause instanceof MerchantCrmDeliveryBusyException) {
            requireOne(campaignMapper.markRecipientRetryableBusy(merchantId, campaignId, recipientId,
                    "投递正在由其他任务处理，请稍后重试"));
            return;
        }
        MerchantCrmCampaign campaign = campaignMapper.selectOwnedCampaign(merchantId, campaignId);
        if (campaign == null) return;
        boolean unknown = cause instanceof MerchantCrmDeliveryOutcomeUnknownException;
        MerchantCrmCouponGrantAdapter.GrantRejectedException rejected =
                cause instanceof MerchantCrmCouponGrantAdapter.GrantRejectedException
                        ? (MerchantCrmCouponGrantAdapter.GrantRejectedException) cause : null;
        String code = unknown ? "DELIVERY_OUTCOME_UNKNOWN"
                : rejected != null ? rejected.getFailureCode()
                : cause instanceof ServiceException && Integer.valueOf(404).equals(((ServiceException) cause).getCode())
                ? "ASSET_INVALID" : "DELIVERY_FAILED";
        String message = unknown ? "投递结果未知，已阻止自动重发"
                : "COUPON_NOT_ISSUED".equals(code)
                ? "优惠券未发出，恢复可发条件后可重试"
                : "投递失败，可重试";
        requireOne(campaignMapper.markRecipientFailed(merchantId, campaignId, recipientId, code, message));
        appendAudit(campaign, recipient, "CRM_CAMPAIGN_DELIVERY_FAILED", null, null);
    }

    private void appendAudit(MerchantCrmCampaign campaign, MerchantCrmCampaignRecipient recipient,
                             String action, Long messageReceipt, Long couponReceipt) {
        BusinessAuditEvent audit = new BusinessAuditEvent();
        audit.setDomainType("MERCHANT");
        audit.setDomainId(campaign.getMerchantRowId());
        audit.setOwnerMemberId(campaign.getOwnerMemberId());
        audit.setActorMemberId(campaign.getActorMemberId());
        audit.setActorRoleCode(campaign.getActorRoleCode());
        audit.setAction(action);
        audit.setTargetType("CRM_RECIPIENT");
        audit.setTargetId(recipient.getId());
        audit.setRelationId(campaign.getId());
        audit.setAfterJson("{\"channel\":\"" + recipient.getChannel() + "\",\"messageReceiptId\":"
                + nullable(messageReceipt) + ",\"couponHistoryId\":" + nullable(couponReceipt) + "}");
        int nextAttempt = (recipient.getAttemptCount() == null ? 0 : recipient.getAttemptCount()) + 1;
        audit.setRequestId("crm-r-" + recipient.getId() + "-a" + nextAttempt);
        audit.setCreateTime(new Date());
        requireOne(auditMapper.insertBusinessAuditEvent(audit));
    }

    private String nullable(Long value) { return value == null ? "null" : String.valueOf(value); }

    private void requireOne(int rows) {
        if (rows != 1) throw new ServiceException("触达状态已变化，请刷新", 409);
    }

    private static final class MerchantCrmDeliveryBusyException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private MerchantCrmDeliveryBusyException() {
            super("投递正在由其他任务处理，请稍后重试");
        }
    }
}
