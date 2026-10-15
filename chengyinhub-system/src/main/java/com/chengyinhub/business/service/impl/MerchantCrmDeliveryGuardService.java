package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.MerchantCrmDeliveryGuard;
import com.chengyinhub.business.mapper.MerchantCrmCampaignMapper;
import com.chengyinhub.common.exception.ServiceException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

/**
 * 日频控 guard 的独立短事务边界。DISPATCHING 必须先提交，外部渠道调用才能开始；
 * 因此崩溃恢复可选择少发一次，但绝不会因为无法判断外部结果而自动重发。
 */
@Service
public class MerchantCrmDeliveryGuardService {
    static final int RESERVATION_LEASE_SECONDS = 120;
    static final int DISPATCH_LEASE_SECONDS = 300;

    @Autowired private MerchantCrmCampaignMapper campaignMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Claim claim(Long merchantId, Long campaignId, Long recipientId,
                       Long memberId, String channel) {
        String leaseToken = token();
        MerchantCrmDeliveryGuard candidate = new MerchantCrmDeliveryGuard();
        candidate.setMerchantId(merchantId);
        candidate.setCampaignId(campaignId);
        candidate.setRecipientId(recipientId);
        candidate.setMemberId(memberId);
        candidate.setChannel(channel);
        candidate.setLeaseToken(leaseToken);
        candidate.setLeaseSeconds(RESERVATION_LEASE_SECONDS);
        if (campaignMapper.insertDeliveryGuard(candidate) == 1) {
            if (candidate.getId() == null) throw new ServiceException("投递额度占用未返回标识", 409);
            return Claim.acquired(candidate.getId(), leaseToken, 0L);
        }

        MerchantCrmDeliveryGuard existing = campaignMapper.selectDeliveryGuardForUpdate(
                merchantId, memberId, channel);
        boolean currentDay = existing != null;
        if (existing == null) {
            existing = campaignMapper.selectDeliveryGuardByRecipientForUpdate(merchantId, campaignId, recipientId);
        }
        if (existing == null) throw new ServiceException("投递额度竞争失败，请稍后重试", 409);
        if (!Objects.equals(merchantId, existing.getMerchantId())
                || !Objects.equals(memberId, existing.getMemberId())
                || !Objects.equals(channel, existing.getChannel())) {
            throw new ServiceException("投递额度归属与冻结名单不一致", 409);
        }
        if (!currentDay && (!Objects.equals(campaignId, existing.getCampaignId())
                || !Objects.equals(recipientId, existing.getRecipientId()))) {
            throw new ServiceException("投递额度回读链路不一致", 409);
        }
        if (!Objects.equals(campaignId, existing.getCampaignId())
                || !Objects.equals(recipientId, existing.getRecipientId())) {
            return Claim.dailyCap();
        }
        if (MerchantCrmDeliveryGuard.DELIVERED.equals(existing.getStatus())) {
            return Claim.delivered(existing.getMessageReceiptId(), existing.getCouponHistoryId());
        }
        if (!currentDay) {
            return Claim.uncertain();
        }
        if (MerchantCrmDeliveryGuard.FAILED_UNCERTAIN.equals(existing.getStatus())) {
            return Claim.uncertain();
        }
        long version = existing.getVersion() == null ? 0L : existing.getVersion();
        if (MerchantCrmDeliveryGuard.DISPATCHING.equals(existing.getStatus())) {
            if (campaignMapper.markStaleDeliveryGuardOutcomeUnknown(existing.getId(), version,
                    "DELIVERY_OUTCOME_UNKNOWN") == 1) {
                return Claim.uncertain();
            }
            return Claim.busy();
        }
        if (MerchantCrmDeliveryGuard.RESERVED.equals(existing.getStatus())
                && campaignMapper.reclaimReservedDeliveryGuard(existing.getId(), version, leaseToken,
                RESERVATION_LEASE_SECONDS) == 1) {
            return Claim.acquired(existing.getId(), leaseToken, version + 1L);
        }
        return Claim.busy();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public Claim beginDispatch(Claim claim) {
        if (claim == null || claim.getOutcome() != Outcome.ACQUIRED) {
            throw new ServiceException("投递额度未持有", 409);
        }
        if (campaignMapper.markDeliveryGuardDispatching(claim.getGuardId(), claim.getLeaseToken(),
                claim.getVersion(), DISPATCH_LEASE_SECONDS) != 1) {
            throw new ServiceException("投递额度租约已变化，请稍后重试", 409);
        }
        return Claim.acquired(claim.getGuardId(), claim.getLeaseToken(), claim.getVersion() + 1L);
    }

    @Transactional(rollbackFor = Exception.class)
    public void markDelivered(Claim dispatching, Long messageReceiptId, Long couponHistoryId) {
        requireDispatching(dispatching);
        if (campaignMapper.markDeliveryGuardDelivered(dispatching.getGuardId(), dispatching.getLeaseToken(),
                dispatching.getVersion(), messageReceiptId, couponHistoryId) != 1) {
            throw new ServiceException("投递回执状态已变化", 409);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void releaseForRetry(Claim dispatching) {
        requireDispatching(dispatching);
        if (campaignMapper.releaseDeliveryGuardForRetry(dispatching.getGuardId(), dispatching.getLeaseToken(),
                dispatching.getVersion()) != 1) {
            throw new ServiceException("投递额度释放失败，已阻止自动重发", 409);
        }
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public void markOutcomeUnknown(Claim dispatching, String failureCode) {
        requireDispatching(dispatching);
        if (campaignMapper.markDeliveryGuardOutcomeUnknown(dispatching.getGuardId(), dispatching.getLeaseToken(),
                dispatching.getVersion(), failureCode) != 1) {
            throw new ServiceException("投递未知态记录失败", 409);
        }
    }

    private void requireDispatching(Claim claim) {
        if (claim == null || claim.getOutcome() != Outcome.ACQUIRED) {
            throw new ServiceException("投递 guard 无效", 409);
        }
    }

    private String token() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    public enum Outcome { ACQUIRED, DAILY_CAP, DELIVERED, UNCERTAIN, BUSY }

    public static final class Claim {
        private final Outcome outcome;
        private final Long guardId;
        private final String leaseToken;
        private final Long version;
        private final Long messageReceiptId;
        private final Long couponHistoryId;

        private Claim(Outcome outcome, Long guardId, String leaseToken, Long version,
                      Long messageReceiptId, Long couponHistoryId) {
            this.outcome = outcome;
            this.guardId = guardId;
            this.leaseToken = leaseToken;
            this.version = version;
            this.messageReceiptId = messageReceiptId;
            this.couponHistoryId = couponHistoryId;
        }

        public static Claim acquired(Long guardId, String leaseToken, Long version) {
            return new Claim(Outcome.ACQUIRED, guardId, leaseToken, version, null, null);
        }

        public static Claim dailyCap() {
            return new Claim(Outcome.DAILY_CAP, null, null, null, null, null);
        }

        public static Claim delivered(Long messageReceiptId, Long couponHistoryId) {
            return new Claim(Outcome.DELIVERED, null, null, null, messageReceiptId, couponHistoryId);
        }

        public static Claim uncertain() {
            return new Claim(Outcome.UNCERTAIN, null, null, null, null, null);
        }

        public static Claim busy() {
            return new Claim(Outcome.BUSY, null, null, null, null, null);
        }

        public Outcome getOutcome() { return outcome; }
        public Long getGuardId() { return guardId; }
        public String getLeaseToken() { return leaseToken; }
        public Long getVersion() { return version; }
        public Long getMessageReceiptId() { return messageReceiptId; }
        public Long getCouponHistoryId() { return couponHistoryId; }
    }
}
