package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ChannelOrderVerification;
import com.chengyinhub.business.domain.ChannelRedemption;
import com.chengyinhub.business.domain.ChannelRefundVerification;
import com.chengyinhub.business.domain.ChannelVoucher;
import com.chengyinhub.business.domain.ChannelVoucherAccount;
import com.chengyinhub.business.domain.dto.ChannelInboundCallbackView;
import com.chengyinhub.business.domain.dto.ChannelVerificationOutcome;
import com.chengyinhub.business.mapper.ChannelOrderVerificationMapper;
import com.chengyinhub.business.mapper.ChannelRedemptionMapper;
import com.chengyinhub.business.mapper.ChannelRefundVerificationMapper;
import com.chengyinhub.business.mapper.ChannelVoucherAccountMapper;
import com.chengyinhub.business.mapper.ChannelVoucherMapper;
import com.chengyinhub.business.mapper.OmsTicketMapper;
import com.chengyinhub.business.service.ChannelRefundCommand;
import com.chengyinhub.business.service.ChannelRevocationOutcome;
import com.chengyinhub.business.service.IChannelRefundService;
import com.chengyinhub.business.service.IMarketingProductFulfillmentService;
import com.chengyinhub.business.service.support.ChannelVoucherCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

/**
 * 渠道退款完成 → 原注册撤销。
 *
 * <p>顺序不可颠倒：先由 provider 证明“渠道侧退款已完成”，再把退款事实交给 M2 的
 * {@link IMarketingProductFulfillmentService#consumeVerifiedChannelRefund}；
 * 该消费方回读本域退款事实后才走既有 C07 CAS 撤准入。
 * 本类不调用微信退款、不把 OTA 退款伪装成微信退款，也不接受只有 reason/requestId 的撤销。
 */
@Service
public class ChannelRefundServiceImpl implements IChannelRefundService {

    private static final Logger log = LoggerFactory.getLogger(ChannelRefundServiceImpl.class);

    private final ChannelVoucherAccountMapper accountMapper;
    private final ChannelOrderVerificationMapper purchaseMapper;
    private final ChannelRefundVerificationMapper refundMapper;
    private final ChannelRefundFactStore factStore;
    private final ChannelVoucherMapper voucherMapper;
    private final ChannelRedemptionMapper redemptionMapper;
    private final OmsTicketMapper ticketMapper;
    private final IMarketingProductFulfillmentService fulfillment;

    public ChannelRefundServiceImpl(ChannelVoucherAccountMapper accountMapper,
                                    ChannelOrderVerificationMapper purchaseMapper,
                                    ChannelRefundVerificationMapper refundMapper,
                                    ChannelRefundFactStore factStore,
                                    ChannelVoucherMapper voucherMapper,
                                    ChannelRedemptionMapper redemptionMapper,
                                    OmsTicketMapper ticketMapper,
                                    IMarketingProductFulfillmentService fulfillment) {
        this.accountMapper = accountMapper;
        this.purchaseMapper = purchaseMapper;
        this.refundMapper = refundMapper;
        this.factStore = factStore;
        this.voucherMapper = voucherMapper;
        this.redemptionMapper = redemptionMapper;
        this.ticketMapper = ticketMapper;
        this.fulfillment = fulfillment;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ChannelInboundCallbackView handleVerifiedRefund(String providerKey,
                                                           ChannelVerificationOutcome outcome,
                                                           String rawBody) {
        ChannelInboundCallbackView view = new ChannelInboundCallbackView();
        view.setAccepted(Boolean.FALSE);
        if (outcome == null || outcome.getEventKind() != ChannelVerificationOutcome.EVENT_REFUND
                || !Boolean.TRUE.equals(outcome.getRefundConfirmed())) {
            view.setReasonCode("REFUND_NOT_CONFIRMED");
            return view;
        }
        String eventId = requireText(outcome.getEventId(), 96, "EVENT_ID_REQUIRED");
        String orderNo = requireText(outcome.getChannelOrderNo(), 96, "CHANNEL_ORDER_NO_REQUIRED");
        String refundNo = requireText(outcome.getChannelRefundNo(), 96, "REFUND_NO_REQUIRED");
        String channelKey = requireText(outcome.getChannelKey(), 40, "CHANNEL_KEY_INVALID");
        if (outcome.getAmount() == null || outcome.getAmount().signum() <= 0
                || outcome.getCurrency() == null || outcome.getCurrency().trim().isEmpty()) {
            throw new IllegalArgumentException("退款金额或币种无效");
        }
        ChannelVoucherAccount account = accountMapper.selectByKey(channelKey);
        if (account == null || !account.issuableAt(new Date())) {
            view.setReasonCode("CHANNEL_INACTIVE");
            return view;
        }
        ChannelOrderVerification purchase =
                purchaseMapper.selectByAccountOrderNo(account.getId(), orderNo);
        if (purchase == null
                || !Integer.valueOf(ChannelOrderVerification.STATUS_VERIFIED).equals(purchase.getStatus())) {
            view.setReasonCode("PURCHASE_NOT_VERIFIED");
            return view;
        }
        boolean sameAmount = purchase.getAmount() != null
                && purchase.getAmount().compareTo(outcome.getAmount()) == 0;
        boolean sameCurrency = purchase.getCurrency() != null
                && purchase.getCurrency().equalsIgnoreCase(outcome.getCurrency());
        boolean fullRefund = sameAmount && sameCurrency
                && !Boolean.FALSE.equals(outcome.getFullRefund());
        if (!sameCurrency || (!sameAmount && Boolean.TRUE.equals(outcome.getFullRefund()))) {
            view.setReasonCode("REFUND_FACT_MISMATCH");
            return view;
        }

        Date now = new Date();
        String rawHash = ChannelVoucherCodec.sha256Hex(rawBody);
        ChannelRefundVerification row = new ChannelRefundVerification();
        row.setAccountId(account.getId());
        row.setPurchaseVerificationId(purchase.getId());
        row.setChannelKey(channelKey);
        row.setProviderKey(providerKey);
        row.setChannelOrderNo(orderNo);
        row.setChannelRefundNo(refundNo);
        row.setEventId(eventId);
        row.setAmount(outcome.getAmount());
        row.setCurrency(outcome.getCurrency().toUpperCase());
        row.setFullRefund(fullRefund ? 1 : 0);
        row.setStatus(fullRefund ? ChannelRefundVerification.STATUS_VERIFIED
                : ChannelRefundVerification.STATUS_REJECTED);
        row.setConsumed(0);
        row.setReasonCode(fullRefund ? null : "PARTIAL_REFUND_UNSUPPORTED");
        row.setRawHash(rawHash);
        row.setRequestId(eventId);
        row.setRequestFingerprint(ChannelVoucherCodec.sha256Hex(
                providerKey + "|" + eventId + "|" + refundNo + "|" + rawHash));
        row.setVerifiedAt(now);
        row.setCreateTime(now);
        row.setUpdateTime(now);
        ChannelRefundVerification stored = factStore.store(row);
        if (!Integer.valueOf(ChannelRefundVerification.STATUS_VERIFIED).equals(stored.getStatus())) {
            view.setReasonCode(stored.getReasonCode() == null
                    ? "REFUND_NOT_SUPPORTED" : stored.getReasonCode());
            view.setEventId(eventId);
            view.setChannelOrderNo(orderNo);
            return view;
        }
        if (Integer.valueOf(1).equals(stored.getConsumed())) {
            view.setAccepted(Boolean.TRUE);
            view.setReplayed(Boolean.TRUE);
            view.setEventId(eventId);
            view.setChannelOrderNo(orderNo);
            view.setStatus(stored.getStatus());
            // 保留首次收敛原因（例如 NO_REVENUE_TO_REVERSE），重投时不假装"刚刚处理过冲销"。
            view.setReasonCode(stored.getReasonCode());
            return view;
        }

        // 购买后从未兑换 ⇒ 没有准入行、也没有已应计收入可冲销。记显式原因并收敛该事实，
        // 不让渠道回调被无限重投，也不假装发生过冲销。
        if (redemptionMapper.selectByChannelOrderNo(orderNo) == null) {
            factStore.markNoRevenueToReverse(stored.getId(), "NO_REVENUE_TO_REVERSE");
            view.setAccepted(Boolean.TRUE);
            view.setReplayed(Boolean.FALSE);
            view.setEventId(eventId);
            view.setChannelOrderNo(orderNo);
            view.setStatus(stored.getStatus());
            view.setReasonCode("NO_REVENUE_TO_REVERSE");
            return view;
        }

        ChannelRefundCommand command = new ChannelRefundCommand();
        command.parentOrderNo = orderNo;
        command.providerKey = providerKey;
        command.channelRefundNo = refundNo;
        command.refundFactId = stored.getId();
        command.refundFactHash = rawHash;
        command.claimedAmount = stored.getAmount();
        command.claimedCurrency = stored.getCurrency();
        command.requestId = eventId;
        // 只有服务端已确认的退款事实才会走到这里；消费方会再次回读本表。
        ChannelRevocationOutcome outcomeAfterConsume = fulfillment.consumeVerifiedChannelRefund(command);
        if (outcomeAfterConsume == null) {
            throw new IllegalStateException("渠道退款消费无结果");
        }
        factStore.markConsumed(stored.getId());
        syncLocalRevocation(orderNo, stored.getPurchaseVerificationId(), now);
        view.setAccepted(Boolean.TRUE);
        view.setReplayed(outcomeAfterConsume.replayed);
        view.setEventId(eventId);
        view.setChannelOrderNo(orderNo);
        view.setStatus(stored.getStatus());
        view.setReasonCode(outcomeAfterConsume.reasonCode);
        return view;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int retryPendingConsumptions(int limit) {
        if (limit <= 0) {
            return 0;
        }
        List<ChannelRefundVerification> rows = refundMapper.selectVerifiedUnconsumed(limit);
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        int driven = 0;
        for (ChannelRefundVerification fact : rows) {
            if (fact == null || fact.getId() == null) {
                continue;
            }
            // 幂等：同一事实重投时，M2 侧已撤销的行会返回 ALREADY_REVOKED，不会二次入队/二次冲销。
            ChannelRefundCommand command = new ChannelRefundCommand();
            command.parentOrderNo = fact.getChannelOrderNo();
            command.providerKey = fact.getProviderKey();
            command.channelRefundNo = fact.getChannelRefundNo();
            command.refundFactId = fact.getId();
            command.refundFactHash = fact.getRawHash();
            command.claimedAmount = fact.getAmount();
            command.claimedCurrency = fact.getCurrency();
            command.requestId = "channel-refund-retry-" + fact.getId();
            if (redemptionMapper.selectByChannelOrderNo(fact.getChannelOrderNo()) == null) {
                factStore.markNoRevenueToReverse(fact.getId(), "NO_REVENUE_TO_REVERSE");
                driven++;
                continue;
            }
            try {
                fulfillment.consumeVerifiedChannelRefund(command);
                factStore.markConsumed(fact.getId());
                driven++;
            } catch (RuntimeException notYet) {
                // 保持 unconsumed，下一轮再驱动；不吞成"已处理"。
                log.warn("[M8] 渠道退款补偿消费未完成 factId={} error={}",
                        fact.getId(), notYet.getClass().getSimpleName());
            }
        }
        return driven;
    }

    private void syncLocalRevocation(String channelOrderNo, Long purchaseVerificationId, Date now) {
        ChannelRedemption redemption = redemptionMapper.selectByChannelOrderNo(channelOrderNo);
        ChannelVoucher voucher = redemption == null
                ? (purchaseVerificationId == null ? null : voucherMapper.selectByVerification(purchaseVerificationId))
                : voucherMapper.selectById(redemption.getVoucherId());
        if (redemption != null
                && !Integer.valueOf(ChannelRedemption.STATUS_REVOKED).equals(redemption.getStatus())) {
            redemptionMapper.markRevoked(redemption.getId(), "CHANNEL_REFUND", now, now);
        }
        if (voucher == null) return;
        voucherMapper.markRevoked(voucher.getId(), "REFUND", now);
        if (voucher.getInventoryTicketId() != null && voucher.getInventoryQuantity() != null
                && (Integer.valueOf(ChannelVoucher.INVENTORY_RESERVED).equals(voucher.getInventoryState())
                || Integer.valueOf(ChannelVoucher.INVENTORY_CONSUMED).equals(voucher.getInventoryState()))) {
            if (ticketMapper.restoreInventory(voucher.getInventoryTicketId(),
                    voucher.getInventoryQuantity().longValue()) == 0) {
                throw new IllegalStateException("渠道退款后库存回收失败 voucherId=" + voucher.getId());
            }
            voucherMapper.markInventoryReleased(voucher.getId(), now);
        }
    }

    private String requireText(String value, int maxLength, String reasonCode) {
        if (value == null || value.trim().isEmpty() || value.trim().length() > maxLength) {
            throw new IllegalArgumentException(reasonCode);
        }
        return value.trim();
    }
}
