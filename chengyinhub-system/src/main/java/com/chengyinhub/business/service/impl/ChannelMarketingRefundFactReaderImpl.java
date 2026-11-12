package com.chengyinhub.business.service.impl;

import com.chengyinhub.business.domain.ChannelOrderVerification;
import com.chengyinhub.business.domain.ChannelRefundVerification;
import com.chengyinhub.business.mapper.ChannelOrderVerificationMapper;
import com.chengyinhub.business.mapper.ChannelRefundVerificationMapper;
import com.chengyinhub.business.service.MarketingChannelRefundFactReader;
import org.springframework.stereotype.Service;

/**
 * 供 M2-F 在退款成功消费时回读渠道退款事实。
 *
 * <p>只认本域 VERIFIED 行，并强制「原付款关联」：退款事实必须挂在一条仍为 VERIFIED 的
 * 购买验证上，且渠道单号/币种一致。调用方传入的金额/hash 只作一致性核对，不构成证明。
 */
@Service
public class ChannelMarketingRefundFactReaderImpl implements MarketingChannelRefundFactReader {

    private final ChannelRefundVerificationMapper refundMapper;
    private final ChannelOrderVerificationMapper purchaseMapper;

    public ChannelMarketingRefundFactReaderImpl(ChannelRefundVerificationMapper refundMapper,
                                                ChannelOrderVerificationMapper purchaseMapper) {
        this.refundMapper = refundMapper;
        this.purchaseMapper = purchaseMapper;
    }

    @Override
    public ChannelRefundFact requireVerifiedRefund(ChannelRefundQuery query) {
        if (query == null || query.refundFactId <= 0 || query.parentOrderNo == null
                || query.providerKey == null || query.channelRefundNo == null) {
            throw new IllegalStateException("CHANNEL_REFUND_QUERY_INVALID");
        }
        ChannelRefundVerification row = refundMapper.selectByIdForUpdate(query.refundFactId);
        if (row == null) {
            throw new IllegalStateException("CHANNEL_REFUND_FACT_NOT_FOUND");
        }
        if (!Integer.valueOf(ChannelRefundVerification.STATUS_VERIFIED).equals(row.getStatus())) {
            throw new IllegalStateException("CHANNEL_REFUND_NOT_VERIFIED");
        }
        if (!query.providerKey.equals(row.getProviderKey())) {
            throw new IllegalStateException("CHANNEL_REFUND_PROVIDER_MISMATCH");
        }
        if (!query.parentOrderNo.equals(row.getChannelOrderNo())) {
            throw new IllegalStateException("CHANNEL_REFUND_ORDER_MISMATCH");
        }
        if (!query.channelRefundNo.equals(row.getChannelRefundNo())) {
            throw new IllegalStateException("CHANNEL_REFUND_NO_MISMATCH");
        }
        if (query.claimedAmount != null && row.getAmount() != null
                && row.getAmount().compareTo(query.claimedAmount) != 0) {
            throw new IllegalStateException("CHANNEL_REFUND_AMOUNT_MISMATCH");
        }
        if (query.claimedCurrency != null && row.getCurrency() != null
                && !row.getCurrency().equalsIgnoreCase(query.claimedCurrency)) {
            throw new IllegalStateException("CHANNEL_REFUND_CURRENCY_MISMATCH");
        }
        // 原付款关联：退款事实必须挂在一条仍为 VERIFIED 的购买验证上，且单号/币种一致。
        if (row.getPurchaseVerificationId() == null) {
            throw new IllegalStateException("CHANNEL_REFUND_PURCHASE_UNLINKED");
        }
        ChannelOrderVerification purchase = purchaseMapper.selectById(row.getPurchaseVerificationId());
        if (purchase == null
                || !Integer.valueOf(ChannelOrderVerification.STATUS_VERIFIED).equals(purchase.getStatus())) {
            throw new IllegalStateException("CHANNEL_REFUND_PURCHASE_NOT_VERIFIED");
        }
        if (!row.getChannelOrderNo().equals(purchase.getChannelOrderNo())) {
            throw new IllegalStateException("CHANNEL_REFUND_PURCHASE_ORDER_MISMATCH");
        }
        if (row.getCurrency() != null && purchase.getCurrency() != null
                && !row.getCurrency().equalsIgnoreCase(purchase.getCurrency())) {
            throw new IllegalStateException("CHANNEL_REFUND_PURCHASE_CURRENCY_MISMATCH");
        }
        // 事实主键与哈希是幂等键:主键取渠道退款校验行 id,哈希取该行落库的原始事实哈希。
        return new ChannelRefundFact(row.getId() == null ? 0L : row.getId(), row.getRawHash(),
                row.getChannelOrderNo(), row.getProviderKey(),
                row.getChannelRefundNo(), row.getAmount(), row.getCurrency(), true,
                Integer.valueOf(1).equals(row.getFullRefund()),
                Integer.valueOf(1).equals(row.getConsumed()));
    }
}
